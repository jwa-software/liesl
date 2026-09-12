;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.mcp
  "Serves the index to an agent over MCP on stdin and stdout: one tool,
  `search`, answered by `liesl.query/hits`.

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

;; What the model reads before deciding whether to call the tool, so it says
;; what the tool is for and how to narrow it.
(def ^:private tool-description
  (str "Search the FHIR specification and get cited, version-tagged excerpts. "
       "Use it for what a resource, element, extension, value set or operation "
       "means and how it is used. Set version to ask about one published "
       "version (STU3, R4, R4B, R5); leave it out to see every version. "
       "The query takes Lucene syntax: bare words, quoted phrases, field:value."))

;; The SDK checks every call against this before the handler runs, so a call
;; without q is refused by the SDK, not by us.
(def ^:private ^McpSchema$JsonSchema input-schema
  (-> (McpSchema$JsonSchema/builder)
      (.type "object")
      (.properties {"q"       {"type" "string"  "description" "The question, in Lucene syntax"}
                    "version" {"type" "string"  "description" "Only this version, e.g. R4"}
                    "kind"    {"type" "string"  "description" "Only this kind of document, e.g. spec"}
                    "limit"   {"type" "integer" "description" "At most this many hits; 10 when absent"}})
      (.required ["q"])
      (.build)))

(def ^:private tool
  (-> (McpSchema$Tool/builder)
      (.name "search")
      (.description tool-description)
      (.inputSchema input-schema)
      (.build)))

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

(defn- answer
  "Turn one call of the tool into what goes back to the model.

  opened   what `query/open` returned
  request  the call; its arguments are a Map with string keys, already
           checked against the input schema

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
           (-> (McpSchema$CallToolResult/builder)
               (.addTextContent (ex-message e))
               (.isError true)
               (.build))))))

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
        handler  (reify BiFunction
                   (apply [_ _exchange request]
                     (answer opened request)))
        server   (-> (McpServer/sync provider)
                     (.serverInfo "liesl" version/version)
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.build)))
                     (.toolCall tool handler)
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
