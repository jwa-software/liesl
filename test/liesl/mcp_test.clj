;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.mcp-test
  "The server on in-memory pipes instead of stdin and stdout, driven with the
  same JSON-RPC lines a client sends, over an index of three small pages."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is]]
            [liesl.corpus    :as corpus]
            [liesl.db        :as db]
            [liesl.document  :as document]
            [liesl.index     :as index]
            [liesl.mcp       :as mcp])
  (:import [io.modelcontextprotocol.json McpJsonDefaults McpJsonMapper]
           [java.io                     BufferedReader InputStreamReader PipedInputStream PipedOutputStream PrintStream]
           [java.nio.file               Files]
           [java.nio.file.attribute     FileAttribute]))

(def ^:private one-source
  {:corpus  "test"
   :sources [{:name "pages" :kind "spec" :base-url "http://127.0.0.1/"}]})

(def ^:private patient
  {:url     "http://127.0.0.1/R4/patient.html"
   :kind    "spec"
   :title   "Resource Patient - Content"
   :body    "Demographics of a patient. The birthTime extension records the time of birth."
   :version "R4"})

(def ^:private birth-time
  {:url     "http://127.0.0.1/R4/extension-patient-birthtime.html"
   :kind    "spec"
   :title   "Extension: birthTime"
   :body    "The time of day that the patient was born."
   :version "R4"})

(def ^:private patient-r5
  {:url     "http://127.0.0.1/R5/patient.html"
   :kind    "spec"
   :title   "Resource Patient - Content"
   :body    "Demographics of a patient, R5 edition, with birthTime too."
   :version "R5"})

(def ^:private ^McpJsonMapper mapper (McpJsonDefaults/getMapper))

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-mcp-test-" ".db")
        silently true]
       (try
         (db/migrate {:db-file file})
         (with-open [conn (db/get-connection (db/db-spec file))]
           (f conn))
         (finally
           (doseq [suffix ["" "-wal" "-shm"]
                   :let   [target (io/file (str file suffix))]]
             (io/delete-file target silently))))))

(defn- with-temp-dir
  "A fresh index directory, removed with its contents afterwards.

  f  the test body, given the directory as a File

  Returns what f returns."
  [f]
  (let [dir      (.toFile (Files/createTempDirectory "liesl-mcp-test-" (make-array FileAttribute 0)))
        silently true]
       (try
         (f dir)
         (finally
           ;; `file-seq` lists a directory before its contents; reversed, children go first.
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file silently))))))

(defn- with-server
  "The three pages indexed, the server started on two pipes, then stopped
  and cleaned up afterwards.

  f  the test body, given a function that sends one JSON-RPC message and
     returns the reply as a map, or nil for a notification

  Returns what f returns."
  [f]
  (with-temp-db
    (fn [conn]
        (with-temp-dir
          (fn [dir]
              (let [source-id (:id (first (corpus/upsert-sources! conn one-source)))]
                   (doseq [page [patient birth-time patient-r5]]
                     (document/upsert! conn source-id page))
                   (let [writer (index/open dir)]
                        (try
                          (index/index-unindexed! conn writer)
                          (finally
                            (index/close writer)))))
              (let [to-server   (PipedOutputStream.)
                    from-server (PipedOutputStream.)
                    running     (mcp/start dir (PipedInputStream. to-server) from-server)
                    out         (PrintStream. to-server true)
                    in          (BufferedReader. (InputStreamReader. (PipedInputStream. from-server)))
                    send!       (fn [message]
                                    (.println out (.writeValueAsString mapper message))
                                    (when (contains? message "id")
                                      (.readValue mapper (str (.readLine in)) java.util.Map)))]
                   (try
                     (f send!)
                     (finally
                       (.close out)
                       (mcp/stop running)))))))))

(defn- initialize!
  "The handshake every client starts with.

  send!  from with-server

  Returns the initialize reply."
  [send!]
  (let [reply (send! {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                      "params"  {"protocolVersion" "2025-06-18"
                                 "capabilities"    {}
                                 "clientInfo"      {"name" "test" "version" "0"}}})]
       (send! {"jsonrpc" "2.0" "method" "notifications/initialized"})
       reply))

(defn- call!
  "One call of the search tool, after the handshake.

  send!      from with-server
  arguments  the tool's arguments, string keys

  Returns the result map: content, a list of {type text}, and isError."
  [send! arguments]
  (get (send! {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
               "params"  {"name" "search" "arguments" arguments}})
       "result"))

(defn- texts
  "The text blocks of a result, in order.

  result  what call! returned

  Returns a vector of strings."
  [result]
  (mapv #(get % "text") (get result "content")))

(deftest the-server-names-itself-and-lists-the-one-tool
  (with-server
    (fn [send!]
        (let [reply (initialize! send!)
              tools (get-in (send! {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}) ["result" "tools"])]
             (is (= "liesl" (get-in reply ["result" "serverInfo" "name"])) "the server says who it is")
             (is (= ["search"] (map #(get % "name") tools))                "one tool")
             (is (= ["q"] (get-in (first tools) ["inputSchema" "required"])) "and only the question is required")))))

(deftest a-call-returns-one-text-block-per-hit-best-first
  (with-server
    (fn [send!]
        (initialize! send!)
        (let [result (call! send! {"q" "birthTime"})
              blocks (texts result)]
             (is (false? (get result "isError")) "a good question is not an error")
             (is (= 3 (count blocks))            "one block per page")
             (is (str/starts-with? (first blocks) (:url birth-time)) "the title hit comes first, its URL on the first line")
             (is (str/includes? (first blocks) "R4 | Extension: birthTime") "version and title on the second line")
             (is (not (str/includes? (first blocks) "\n\n")) "the snippet is one line")))))

(deftest a-version-argument-narrows-the-call
  (with-server
    (fn [send!]
        (initialize! send!)
        (let [blocks (texts (call! send! {"q" "birthTime" "version" "R4" "limit" 5}))]
             (is (= 2 (count blocks))                                      "the R5 page is out")
             (is (not-any? #(str/includes? % (:url patient-r5)) blocks)   "and its URL appears nowhere")))))

(deftest a-call-without-a-question-is-refused-by-the-schema
  (with-server
    (fn [send!]
        (initialize! send!)
        (let [result (call! send! {"version" "R4"})]
             (is (true? (get result "isError")) "the SDK checks the schema before the handler runs")
             (is (str/includes? (first (texts result)) "q") "and names the missing argument")))))

(deftest a-question-lucene-cannot-parse-is-an-error-result-not-a-crash
  (with-server
    (fn [send!]
        (initialize! send!)
        (let [result (call! send! {"q" "\"unbalanced"})]
             (is (true? (get result "isError")) "an error result, and the server is still up")
             (is (str/starts-with? (first (texts result)) "Cannot parse query") "carrying the parser's message"))
        (is (false? (get (call! send! {"q" "birthTime"}) "isError")) "the next call works"))))

(deftest no-hits-says-so
  (with-server
    (fn [send!]
        (initialize! send!)
        (is (= ["No hits."] (texts (call! send! {"q" "haemoglobin"}))) "one block, saying nothing was found"))))
