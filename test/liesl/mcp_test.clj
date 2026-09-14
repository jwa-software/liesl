;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.mcp-test
  "The server on in-memory pipes instead of stdin and stdout, driven with the
  same JSON-RPC lines a client sends, over an index of three small pages and
  the edges between them."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is]]
            [liesl.corpus    :as corpus]
            [liesl.db        :as db]
            [liesl.document  :as document]
            [liesl.index     :as index]
            [liesl.link      :as link]
            [liesl.mcp       :as mcp])
  (:import [io.modelcontextprotocol.json McpJsonDefaults McpJsonMapper]
           [java.io                     BufferedReader InputStreamReader PipedInputStream PipedOutputStream PrintStream]
           [java.nio.file               Files]
           [java.nio.file.attribute     FileAttribute]))

;; Published by version, so the linker can pair the pages.
(def ^:private one-source
  {:corpus   "test"
   :versions ["R4" "R5"]
   :sources  [{:name     "pages"
               :kind     "spec"
               :base-url "http://127.0.0.1/"
               :config   {:version-path "{version}/" :archive "pages.zip"}}]})

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

  f  the test body, given {:send! <fn> :burst! <fn>}: send! sends one
     JSON-RPC message and returns the reply as a map, or nil for a
     notification; burst! sends several without waiting and returns their
     replies in the order they arrive

  Returns what f returns."
  [f]
  (with-temp-db
    (fn [conn]
        (with-temp-dir
          (fn [dir]
              (let [source (first (corpus/upsert-sources! conn one-source))]
                   (doseq [page [patient birth-time patient-r5]]
                     (document/upsert! conn (:id source) page))
                   (let [writer (index/open dir)]
                        (try
                          (index/index-unindexed! conn writer)
                          (finally
                            (index/close writer))))
                   (link/link-versions! conn {:source source :versions ["R4" "R5"]}))
              (let [to-server   (PipedOutputStream.)
                    from-server (PipedOutputStream.)
                    running     (mcp/start dir conn (PipedInputStream. to-server) from-server)
                    out         (PrintStream. to-server true)
                    in          (BufferedReader. (InputStreamReader. (PipedInputStream. from-server)))
                    reply!      (fn []
                                    (.readValue mapper (str (.readLine in)) java.util.Map))
                    send!       (fn [message]
                                    (.println out (.writeValueAsString mapper message))
                                    (when (contains? message "id")
                                      (reply!)))
                    burst!      (fn [messages]
                                    (doseq [message messages]
                                      (.println out (.writeValueAsString mapper message)))
                                    (vec (repeatedly (count messages) reply!)))]
                   (try
                     (f {:send! send! :burst! burst!})
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
  "One call of a tool, after the handshake.

  send!      from with-server
  tool       the tool's name: \"search\", \"get\" or \"related\"
  arguments  the tool's arguments, string keys

  Returns the result map: content, a list of {type text}, and isError."
  [send! tool arguments]
  (get (send! {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
               "params"  {"name" tool "arguments" arguments}})
       "result"))

(defn- texts
  "The text blocks of a result, in order.

  result  what call! returned

  Returns a vector of strings."
  [result]
  (mapv #(get % "text") (get result "content")))

(deftest the-server-names-itself-and-lists-its-three-tools
  (with-server
    (fn [{:keys [send!]}]
        (let [reply (initialize! send!)
              tools (get-in (send! {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}) ["result" "tools"])
              required (into {} (map (fn [tool] [(get tool "name") (vec (get-in tool ["inputSchema" "required"]))])) tools)]
             (is (= "liesl" (get-in reply ["result" "serverInfo" "name"])) "the server says who it is")
             (is (= {"search" ["q"] "get" ["url"] "related" ["url"]} required) "three tools, each with one required argument")))))

(deftest a-call-returns-one-text-block-per-hit-best-first
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "search" {"q" "birthTime"})
              blocks (texts result)]
             (is (false? (get result "isError")) "a good question is not an error")
             (is (= 3 (count blocks))            "one block per page")
             (is (str/starts-with? (first blocks) (:url birth-time)) "the title hit comes first, its URL on the first line")
             (is (str/includes? (first blocks) "R4 | Extension: birthTime") "version and title on the second line")
             (is (not (str/includes? (first blocks) "\n\n")) "the snippet is one line")))))

(deftest a-version-argument-narrows-the-call
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [blocks (texts (call! send! "search" {"q" "birthTime" "version" "R4" "limit" 5}))]
             (is (= 2 (count blocks))                                      "the R5 page is out")
             (is (not-any? #(str/includes? % (:url patient-r5)) blocks)   "and its URL appears nowhere")))))

(deftest a-call-without-a-question-is-refused-by-the-schema
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "search" {"version" "R4"})]
             (is (true? (get result "isError")) "the SDK checks the schema before the handler runs")
             (is (str/includes? (first (texts result)) "q") "and names the missing argument")))))

(deftest a-question-lucene-cannot-parse-is-an-error-result-not-a-crash
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "search" {"q" "\"unbalanced"})]
             (is (true? (get result "isError")) "an error result, and the server is still up")
             (is (str/starts-with? (first (texts result)) "Cannot parse query") "carrying the parser's message"))
        (is (false? (get (call! send! "search" {"q" "birthTime"}) "isError")) "the next call works"))))

(deftest no-hits-says-so
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (is (= ["No hits."] (texts (call! send! "search" {"q" "haemoglobin"}))) "one block, saying nothing was found"))))

(deftest get-returns-the-whole-page-with-its-citation-first
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "get" {"url" (:url patient)})]
             (is (false? (get result "isError")) "a known page is not an error")
             (is (= [(str (:url patient) "\nR4 | " (:title patient) "\n\n" (:body patient))] (texts result))
                 "url, then version and title, a blank line, then the body, nothing cut")))))

(deftest get-cuts-a-long-page-at-the-limit-and-says-so
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [text (first (texts (call! send! "get" {"url" (:url patient) "limit" 12})))]
             (is (str/includes? text (str "\n\n" (subs (:body patient) 0 12) "\n")) "the first twelve characters of the body")
             (is (str/ends-with? text (format "[cut at 12 of %d characters]" (count (:body patient)))) "and a last line saying so")))))

(deftest get-of-an-unknown-url-is-an-error-result
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "get" {"url" "http://127.0.0.1/R4/nowhere.html"})]
             (is (true? (get result "isError")) "an error result, and the server is still up")
             (is (= ["No page at http://127.0.0.1/R4/nowhere.html"] (texts result)) "naming the url")))))

(deftest related-lists-the-same-page-in-the-other-versions
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "related" {"url" (:url patient)})]
             (is (false? (get result "isError")) "a known page is not an error")
             (is (= [(str (:url patient-r5) "\nR5 | " (:title patient-r5))] (texts result))
                 "the R5 page, as a citation: url, then version and title")))))

(deftest related-of-a-page-in-one-version-only-says-so
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (is (= ["No other versions."] (texts (call! send! "related" {"url" (:url birth-time)})))
            "the extension exists in R4 alone"))))

(deftest calls-sent-together-are-all-answered
  ;; The SDK's outbound queue drops an answer when two are emitted at once
  ;; from its thread pool; the server runs handlers one after another on the
  ;; reading thread instead, and this is the test that would catch a return
  ;; to the default.
  (with-server
    (fn [{:keys [send! burst!]}]
        (initialize! send!)
        (let [call    (fn [id tool arguments]
                          {"jsonrpc" "2.0" "id" id "method" "tools/call"
                           "params"  {"name" tool "arguments" arguments}})
              replies (burst! [(call 10 "related" {"url" (:url patient)})
                               (call 11 "get"     {"url" (:url birth-time)})
                               (call 12 "search"  {"q" "birthTime"})])]
             (is (= #{10 11 12} (set (map #(get % "id") replies)))          "three answers for three calls")
             (is (every? #(false? (get-in % ["result" "isError"])) replies) "none of them an error")))))

(deftest related-of-an-unknown-url-is-an-error-result
  (with-server
    (fn [{:keys [send!]}]
        (initialize! send!)
        (let [result (call! send! "related" {"url" "http://127.0.0.1/R4/nowhere.html"})]
             (is (true? (get result "isError")) "an error result")
             (is (= ["No page at http://127.0.0.1/R4/nowhere.html"] (texts result)) "naming the url")))))
