;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.mcp
  "Serves the index to an agent over MCP on stdin and stdout: two tools,
  `search`, answered by `liesl.query/hits`, and `get`, answered by
  `liesl.query/page`.

  stdout is the wire. A stray `println` here would land inside the protocol
  and the client would drop the connection without saying why, so nothing in
  this namespace prints; the SDK's own messages go to stderr through slf4j."
  (:require [clojure.string :as str]
            [liesl.index    :as index]
            [liesl.query    :as query]
            [liesl.version  :as version])
  (:import [io.modelcontextprotocol.json             McpJsonDefaults]
           [io.modelcontextprotocol.server           McpServer McpSyncServer]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.spec             McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$JsonSchema McpSchema$ServerCapabilities McpSchema$Tool]
           [java.io                                  InputStream OutputStream]
           [java.util.function                       BiFunction]))

;; What the model reads before deciding whether to call a tool, so each
;; description says what the tool is for and how to narrow it.
(def ^:private search-description
  (str "Search the FHIR specification and get cited, version-tagged excerpts. "
       "Use it for what a resource, element, extension, value set or operation "
       "means and how it is used. Set version to ask about one published "
       "version (STU3, R4, R4B, R5); leave it out to see every version. "
       "The query takes Lucene syntax: bare words, quoted phrases, field:value."))

(def ^:private get-description
  (str "Read one page of the FHIR specification in full, by the url a search "
       "hit gave. The first two lines are the citation: the url, then the "
       "version and title. Long pages are cut at limit characters, 20000 "
       "when absent, and say so at the end."))

;; The SDK checks every call against its schema before the handler runs, so
;; a call without the required argument is refused by the SDK, not by us.
(def ^:private ^McpSchema$JsonSchema search-schema
  (-> (McpSchema$JsonSchema/builder)
      (.type "object")
      (.properties {"q"       {"type" "string"  "description" "The question, in Lucene syntax"}
                    "version" {"type" "string"  "description" "Only this version, e.g. R4"}
                    "kind"    {"type" "string"  "description" "Only this kind of document, e.g. spec"}
                    "limit"   {"type" "integer" "description" "At most this many hits; 10 when absent"}})
      (.required ["q"])
      (.build)))

(def ^:private ^McpSchema$JsonSchema get-schema
  (-> (McpSchema$JsonSchema/builder)
      (.type "object")
      (.properties {"url"   {"type" "string"  "description" "The page's url, as a search hit gave it"}
                    "limit" {"type" "integer" "description" "At most this many characters of the page; 20000 when absent"}})
      (.required ["url"])
      (.build)))

(def ^:private search-tool
  (-> (McpSchema$Tool/builder)
      (.name "search")
      (.description search-description)
      (.inputSchema search-schema)
      (.build)))

(def ^:private get-tool
  (-> (McpSchema$Tool/builder)
      (.name "get")
      (.description get-description)
      (.inputSchema get-schema)
      (.build)))

;; A whole page can be 1.8 million characters (the namespace registry); this
;; is what goes back when the caller names no limit.
(def ^:private default-page-limit 20000)

;; ---- Pure helpers: no I/O ----

(defn- hit->text
  "Turn one hit into the lines the model reads: where the page is, which
  version and title, then the passage that matched on one line.

  hit  one map from `query/hits`

  Returns a string of three lines."
  [{:keys [url version title snippet]}]
  (str url "\n"
       (or version "-") " | " (or title "") "\n"
       (str/replace snippet "\n" " ")))

(defn- page->text
  "Turn one page into what the model reads: the citation, a blank line, then
  the body, cut when it is longer than the caller wants.

  page   one map from `query/page`
  limit  at most this many characters of the body

  Returns a string; a cut body ends with a line saying how much of it this is."
  [{:keys [url version title body]} limit]
  (let [cut? (> (count body) limit)]
       (str url "\n"
            (or version "-") " | " (or title "") "\n"
            "\n"
            (if cut? (subs body 0 limit) body)
            (when cut? (format "\n[cut at %d of %d characters]" limit (count body))))))

(defn- error-result
  "The result that tells the model a call failed, and why.

  message  what went wrong, in one line

  Returns a `CallToolResult` with `isError` set."
  ^McpSchema$CallToolResult [^String message]
  (-> (McpSchema$CallToolResult/builder)
      (.addTextContent message)
      (.isError true)
      (.build)))

(defn- answer-search
  "Turn one call of `search` into what goes back to the model.

  opened   what `query/open` returned
  request  the call; its arguments are a Map with string keys, already
           checked against the schema

  Returns a `CallToolResult`: one text block per hit, `No hits.` when there
  is none, and an error result carrying the message when the question cannot
  be parsed."
  ^McpSchema$CallToolResult [opened ^McpSchema$CallToolRequest request]
  (let [args (.arguments request)
        ask  {:q       (get args "q")
              :version (get args "version")
              :kind    (get args "kind")
              :limit   (get args "limit")}]
       (try
         (let [found   (query/hits opened ask)
               builder (McpSchema$CallToolResult/builder)]
              (if (seq found)
                (doseq [hit found]
                  (.addTextContent builder (hit->text hit)))
                (.addTextContent builder "No hits."))
              (.build builder))
         (catch clojure.lang.ExceptionInfo e
           (error-result (ex-message e))))))

(defn- answer-get
  "Turn one call of `get` into what goes back to the model.

  opened   what `query/open` returned
  request  the call; its arguments are a Map with string keys, already
           checked against the schema

  Returns a `CallToolResult`: one text block holding the page, or an error
  result naming the url when no page has it."
  ^McpSchema$CallToolResult [opened ^McpSchema$CallToolRequest request]
  (let [args  (.arguments request)
        url   (get args "url")
        limit (int (or (get args "limit") default-page-limit))]
       (if-let [found (query/page opened url)]
         (-> (McpSchema$CallToolResult/builder)
             (.addTextContent (page->text found limit))
             (.build))
         (error-result (str "No page at " url)))))

;; ---- Public: start and stop, then the command ----

(defn start
  "Open the index for reading and bring the server up on two streams.

  dir  the index directory
  in   where requests arrive; `System/in` in use, a pipe in tests
  out  where answers go; `System/out` in use, a pipe in tests

  Returns {:server <the McpSyncServer> :opened <what query/open returned>},
  for `stop`. Returns at once: the server reads `in` on threads of its own,
  which end when `in` is closed."
  [dir ^InputStream in ^OutputStream out]
  (let [opened   (query/open dir)
        provider (StdioServerTransportProvider. (McpJsonDefaults/getMapper) in out)
        on-search (reify BiFunction
                    (apply [_ _exchange request]
                      (answer-search opened request)))
        on-get    (reify BiFunction
                    (apply [_ _exchange request]
                      (answer-get opened request)))
        server    (-> (McpServer/sync provider)
                      (.serverInfo "liesl" version/version)
                      (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                         (.tools true)
                                         (.build)))
                      (.toolCall search-tool on-search)
                      (.toolCall get-tool    on-get)
                      (.build))]
       {:server server :opened opened}))

(defn stop
  "Close what `start` returned: the server first, then the index.

  running  what `start` returned

  Returns nil."
  [{:keys [^McpSyncServer server opened]}]
  (.closeGracefully server)
  (query/close opened))

(defn -main
  "`clojure -M:mcp`: serve the index under `data/index` on stdin and stdout.

  Returns as soon as the server is up. The server's own threads keep the
  process alive until the client closes stdin, and a shutdown hook closes the
  index on the way out."
  [& _]
  (let [running (start (index/index-dir) System/in System/out)]
       (.addShutdownHook (Runtime/getRuntime)
                         (Thread. ^Runnable (fn [] (stop running))))))
