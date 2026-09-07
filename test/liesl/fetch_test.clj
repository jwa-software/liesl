;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.fetch-test
  "Tested against a real HTTP server on a loopback port rather than a mock,
  because what is being checked is conditional requests and 304s -- behaviour
  that only exists between two parties that both speak HTTP."
  (:require [clojure.java.io      :as io]
            [liesl.db             :as db]
            [liesl.fetch          :as fetch]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.test         :refer [deftest is use-fixtures]])
  (:import [com.sun.net.httpserver   HttpServer HttpHandler]
           [java.net                 InetSocketAddress]
           [java.nio.file            Files]
           [java.nio.file.attribute  FileAttribute]))

(def ^:private ^:dynamic *db-spec*   nil)
(def ^:private ^:dynamic *source-id* nil)

(defn- insert-a-source!
  "Create the one source the tests fetch through. RETURNING saves a second
  statement to read back what we just wrote.

  conn  an open connection

  Returns the new row's id."
  [conn]
  (:id (jdbc/execute-one!
        conn
        [(str "INSERT INTO source (corpus, name, kind, base_url) "
              "VALUES ('test', 'pages', 'spec', 'http://127.0.0.1/') "
              "RETURNING id")]
        {:builder-fn rs/as-unqualified-lower-maps})))

(defn- with-temp-db
  "A fresh migrated database per test, with one source row -- fetch_state
  references it, and get-connection enforces that.

  f  the test, run with *db-spec* and *source-id* bound

  Returns nothing the caller needs; a clojure.test fixture."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-fetch-test-" ".db")
        silently true]
    (try
      (db/migrate {:db-file file})
      (binding [*db-spec* (db/db-spec file)]
        (with-open [conn (db/get-connection *db-spec*)]
          (binding [*source-id* (insert-a-source! conn)]
            (f))))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]
                :let   [target (io/file (str file suffix))]]
          (io/delete-file target silently))))))

(defn- respond!
  "Ask handler-fn for [status body headers] and send it back. The exchange is
  one HTTP interaction, holding both the request to read and the response to
  write.

  exchange    the HttpExchange
  handler-fn  given the exchange, returns [status body headers]; body may be nil

  Returns nothing the caller needs."
  [exchange handler-fn]
  (let [[status body headers] (handler-fn exchange)
        no-body               -1
        length                (or (some-> body .getBytes count) no-body)]
    (doseq [[k v] headers] (-> exchange .getResponseHeaders (.add k v)))
    (.sendResponseHeaders exchange status length)
    (when body
      (with-open [out (.getResponseBody exchange)] (.write out (.getBytes body))))))

(defn- with-server
  "Start a server on a free local port, hand its URL to f, and stop it
  afterwards.

  handler-fn  called once per request with the HttpExchange; the
              [status body headers] it returns becomes the response
  f           given the URL of the one page the server serves

  Returns what f returns."
  [handler-fn f]
  (let [loopback        "127.0.0.1"
        path            "/"
        any-free-port   0
        default-backlog 0
        no-wait         0
        server          (HttpServer/create (InetSocketAddress. loopback any-free-port) default-backlog)
        ;; A one-off object implementing HttpHandler, which is Clojure's anonymous class.
        ;; In Java:
        ;;   new HttpHandler() { public void handle(HttpExchange e) { ... } }
        handler         (reify HttpHandler (handle [_this exchange] (respond! exchange handler-fn)))]
    (.createContext server path handler)
    (.start server)
    (try
      (f (str "http://" loopback ":" (-> server .getAddress .getPort) "/page"))
      (finally (.stop server no-wait)))))

(defn- get-fetch-state!
  "The fetch_state row for a URL.

  url  the row key

  Returns the whole row as a map, or nil when there is none."
  [url]
  (with-open [conn (db/get-connection *db-spec*)]
    (jdbc/execute-one! conn
                       ["SELECT * FROM fetch_state WHERE url = ?" url]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- fetch!
  "fetch-url! against the test database and source, as :string unless told
  otherwise.

  url   what to fetch
  opts  keyword arguments merged over the defaults, e.g. :as :file :opts {...}

  Returns what fetch-url! returns."
  [url & {:as opts}]
  (with-open [conn (db/get-connection *db-spec*)]
    (fetch/fetch-url! conn (merge {:url url :source-id *source-id* :as :string} opts))))

(defn- with-temp-dir
  "A fresh directory for downloads, removed with its contents afterwards.

  f  the test body, given the directory as a File

  Returns what f returns."
  [f]
  (let [dir      (.toFile (Files/createTempDirectory "liesl-fetch-test-" (make-array FileAttribute 0)))
        silently true]
    (try
      (f dir)
      (finally
        ;; file-seq lists a directory before its contents; reversed, children go first.
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file silently))))))

(use-fixtures :each with-temp-db)

(deftest a-first-fetch-returns-the-body-and-records-the-validators
  (with-server
    (fn [_] [200 "hello" {"ETag" "\"v1\"" "Last-Modified" "Wed, 01 Jan 2025 00:00:00 GMT"}])
    (fn [url]
      (is (= {:status 200 :body "hello"} (fetch! url)))
      (let [state (get-fetch-state! url)]
        (is (= "\"v1\""                        (:etag          state)))
        (is (= "Wed, 01 Jan 2025 00:00:00 GMT" (:last_modified state)))
        (is (= 0                               (:failures      state)))
        (is (some?                             (:content_hash  state)) "the body must be hashed")))))

(deftest a-second-fetch-sends-the-validator-and-accepts-304
  (let [seen (atom [])]
    (with-server
      (fn [exchange]
        (let [if-none-match (.getFirst (.getRequestHeaders exchange) "If-None-Match")]
          (swap! seen conj if-none-match)
          (if (= "\"v1\"" if-none-match)
            [304 nil {}]
            [200 "hello" {"ETag" "\"v1\""}])))
      (fn [url]
        (is (= {:status 200 :body "hello"} (fetch! url)) "the first fetch has no validator to send, so the body comes back")
        (is (= {:status 304}               (fetch! url)) "an unchanged page returns no body")
        ;; Bound after the fetches: before them there is no row to read.
        (let [state (get-fetch-state! url)
              etag  (:etag state)]
          (is (= [nil "\"v1\""] @seen) "the first request carries no validator, the second carries the etag")
          (is (= "\"v1\""       etag)  "a 304 must not erase the etag it was answered with"))))))

(deftest every-request-names-liesl
  (let [seen (atom nil)]
    (with-server
      (fn [exchange]
        (reset! seen (.getFirst (.getRequestHeaders exchange) "User-Agent"))
        [200 "hello" {}])
      (fn [url]
        ;; Called for the request it makes, not for what it returns: the
        ;; User-Agent is only visible from the server's side.
        (fetch! url)
        (is (re-matches #"liesl/\d+\.\d+\.\d+ \(\+https://github\.com/jwa-software/liesl\)" @seen)
            "the server operator must be able to tell who is crawling them")))))

(deftest a-failure-is-counted-and-does-not-throw
  (with-server
    (fn [_] [404 "gone" {}])
    (fn [url]
      (is (= {:status 404} (fetch! url))                      "an HTTP status is a result, not an exception")
      (is (= 1             (:failures (get-fetch-state! url))) "a 4xx increments the failure count, so a caller can back off")
      (is (= 404           (:status (get-fetch-state! url)))   "the status is kept as it came back, not flattened to a flag"))))

(deftest a-download-lands-in-the-file-and-is-hashed
  (with-temp-dir
    (fn [dir]
      (with-server
        (fn [_] [200 "archive bytes" {}])
        (fn [url]
          (let [target (io/file dir "archive.bin")]
            (is (= {:status 200 :file target} (fetch! url :as :file :opts {:file target})) "the result names the file instead of carrying the body")
            (is (= "archive bytes" (slurp target))                         "the body went to the file")
            (is (some? (:content_hash (get-fetch-state! url)))             "the file must be hashed")
            (is (= ["archive.bin"] (vec (.list dir)))                      "no temp file is left beside it")))))))

(deftest a-304-leaves-the-previous-download-intact
  (with-temp-dir
    (fn [dir]
      (with-server
        (fn [exchange]
          (if (.getFirst (.getRequestHeaders exchange) "If-None-Match")
            [304 nil {}]
            [200 "first body" {"ETag" "\"v1\""}]))
        (fn [url]
          (let [target (io/file dir "archive.bin")]
            (fetch! url :as :file :opts {:file target})
            (is (= {:status 304} (fetch! url :as :file :opts {:file target})) "the second fetch carries the etag and gets no body")
            (is (= "first body" (slurp target))                 "a 304 has no body, so the first download must survive")
            (is (= ["archive.bin"] (vec (.list dir)))           "and no temp file is left beside it")))))))

(deftest a-failed-download-leaves-no-file-behind
  (with-temp-dir
    (fn [dir]
      (with-server
        (fn [_] [404 "gone" {}])
        (fn [url]
          (let [target (io/file dir "archive.bin")]
            (is (= {:status 404} (fetch! url :as :file :opts {:file target})) "an HTTP status is a result, not an exception")
            (is (empty? (vec (.list dir)))                      "neither the target nor a temp file may exist")))))))

(deftest an-unknown-as-is-refused-before-any-request
  ;; No server: the check runs before anything is sent, so the URL is never touched.
  (let [info (try (fetch! "http://127.0.0.1/never-requested" :as :pdf) nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the fetch must fail")
    (is (= {:as :pdf :allowed #{:string :file}} info) "the error names the value and what would have been accepted")))

(deftest the-delay-is-waited-out-before-the-request
  (with-server
    (fn [_] [200 "hello" {}])
    (fn [url]
      (let [started (System/currentTimeMillis)]
        (fetch! url :delay-ms 100)
        (is (>= (- (System/currentTimeMillis) started) 100))))))
