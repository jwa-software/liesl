;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.mcp
  "Serves the corpus to an agent over MCP on stdin and stdout: three tools,
  `search` and `get` answered from the index by `liesl.query`, and `related`
  answered from the database by `liesl.link`.

  stdout is the wire. A stray `println` here would land inside the protocol
  and the client would drop the connection without saying why, so nothing in
  this namespace prints; the SDK's own messages go to stderr through slf4j."
  (:require [clojure.string :as str]
            [liesl.db       :as db]
            [liesl.index    :as index]
            [liesl.link     :as link]
            [liesl.query    :as query]
            [liesl.version  :as version])
  (:import [io.modelcontextprotocol.json             McpJsonDefaults]
           [io.modelcontextprotocol.server           McpServer McpSyncServer]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.spec             McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$JsonSchema McpSchema$ServerCapabilities McpSchema$Tool]
           [java.io                                  InputStream OutputStream]
           [java.sql                                 Connection]
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

(def ^:private related-description
  (str "Find the same page in the other published versions, by the url a "
       "search hit gave. Use it to compare versions or to check whether a "
       "page exists in a version at all: a page with no counterpart says so."))

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

(def ^:private ^McpSchema$JsonSchema related-schema
  (-> (McpSchema$JsonSchema/builder)
      (.type "object")
      (.properties {"url" {"type" "string" "description" "The page's url, as a search hit gave it"}})
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

(def ^:private related-tool
  (-> (McpSchema$Tool/builder)
      (.name "related")
      (.description related-description)
      (.inputSchema related-schema)
      (.build)))

;; A whole page can be 1.8 million characters (the namespace registry); this
;; is what goes back when the caller names no limit.
(def ^:private default-page-limit 20000)

;; ---- Pure helpers: no I/O ----

(defn- citation
  "The two lines that name a page: where it is, then which version and title.

  page  any map with :url, :version and :title

  Returns a string of two lines, no trailing newline."
  [{:keys [url version title]}]
  (str url "\n"
       (or version "-") " | " (or title "")))

(defn- hit->text
  "Turn one hit into the lines the model reads: the citation, then the
  passage that matched on one line.

  hit  one map from `query/hits`

  Returns a string of three lines."
  [{:keys [snippet] :as hit}]
  (str (citation hit) "\n"
       (str/replace snippet "\n" " ")))

(defn- page->text
  "Turn one page into what the model reads: the citation, a blank line, then
  the body, cut when it is longer than the caller wants.

  page   one map from `query/page`
  limit  at most this many characters of the body

  Returns a string; a cut body ends with a line saying how much of it this is."
  [{:keys [body] :as page} limit]
  (let [cut? (> (count body) limit)]
       (str (citation page) "\n"
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

(defn- text-result
  "The result that carries one text block per item, or one block saying
  there is none.

  texts     the blocks, in order
  when-none what to say when texts is empty

  Returns a `CallToolResult`."
  ^McpSchema$CallToolResult [texts ^String when-none]
  (let [builder (McpSchema$CallToolResult/builder)]
       (if (seq texts)
         (doseq [text texts]
           (.addTextContent builder text))
         (.addTextContent builder when-none))
       (.build builder)))

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
         (text-result (map hit->text (query/hits opened ask)) "No hits.")
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
         (text-result [(page->text found limit)] "")
         (error-result (str "No page at " url)))))

(defn- answer-related
  "Turn one call of `related` into what goes back to the model.

  conn     an open connection
  request  the call; its arguments are a Map with string keys, already
           checked against the schema

  Returns a `CallToolResult`: one text block per counterpart, its citation;
  `No other versions.` when the page stands alone; an error result naming the
  url when no page has it."
  ^McpSchema$CallToolResult [conn ^McpSchema$CallToolRequest request]
  (let [url (get (.arguments request) "url")]
       (if-let [counterparts (link/related conn url)]
         (text-result (map citation counterparts) "No other versions.")
         (error-result (str "No page at " url)))))

;; ---- Public: start and stop, then the command ----

(defn start
  "Open the index for reading and bring the server up on two streams.

  dir   the index directory
  conn  an open connection, for the edges; the caller closes it after `stop`
  in    where requests arrive; `System/in` in use, a pipe in tests
  out   where answers go; `System/out` in use, a pipe in tests

  Returns {:server <the McpSyncServer> :opened <what query/open returned>},
  for `stop`. Returns at once: the server reads `in` on threads of its own,
  which end when `in` is closed."
  [dir ^Connection conn ^InputStream in ^OutputStream out]
  (let [opened     (query/open dir)
        provider   (StdioServerTransportProvider. (McpJsonDefaults/getMapper) in out)
        on-search  (reify BiFunction
                     (apply [_ _exchange request]
                       (answer-search opened request)))
        on-get     (reify BiFunction
                     (apply [_ _exchange request]
                       (answer-get opened request)))
        on-related (reify BiFunction
                     (apply [_ _exchange request]
                       (answer-related conn request)))
        server     (-> (McpServer/sync provider)
                       (.serverInfo "liesl" version/version)
                       (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                          (.tools true)
                                          (.build)))
                       ;; Handlers run on the thread that read the request, one
                       ;; call after another. Left to its default, the SDK runs
                       ;; each on a thread pool, and two finishing together emit
                       ;; into the stdio transport's outbound sink at the same
                       ;; time, which a reactor sink refuses (FAIL_NON_SERIALIZED):
                       ;; "Failed to enqueue message" on stderr and a reply the
                       ;; client never gets. Our calls take milliseconds, so one
                       ;; at a time costs nothing.
                       (.immediateExecution true)
                       (.toolCall search-tool  on-search)
                       (.toolCall get-tool     on-get)
                       (.toolCall related-tool on-related)
                       (.build))]
       {:server server :opened opened}))

(defn stop
  "Close what `start` returned: the server first, then the index. The
  connection is the caller's to close.

  running  what `start` returned

  Returns nil."
  [{:keys [^McpSyncServer server opened]}]
  (.closeGracefully server)
  (query/close opened))

(defn -main
  "`clojure -M:mcp`: serve the index under `data/index` and the edges in the
  database on stdin and stdout.

  Returns as soon as the server is up. The server's own threads keep the
  process alive until the client closes stdin, and a shutdown hook closes the
  index and the connection on the way out."
  [& _]
  (let [conn    (db/get-connection)
        running (start (index/index-dir) conn System/in System/out)]
       (.addShutdownHook (Runtime/getRuntime)
                         (Thread. ^Runnable (fn []
                                                (stop running)
                                                (.close conn))))))
