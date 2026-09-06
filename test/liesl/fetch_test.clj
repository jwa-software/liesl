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
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net               InetSocketAddress]))

(def ^:private ^:dynamic *db-spec*   nil)
(def ^:private ^:dynamic *source-id* nil)

(defn- insert-a-source!
  "Create the one source the tests fetch through, and return its id. RETURNING
  saves a second statement to read back what we just wrote."
  [conn]
  (:id (jdbc/execute-one!
        conn
        [(str "INSERT INTO source (corpus, name, kind, base_url) "
              "VALUES ('test', 'pages', 'spec', 'http://127.0.0.1/') "
              "RETURNING id")]
        {:builder-fn rs/as-unqualified-lower-maps})))

(defn- with-temp-db
  "A fresh migrated database per test, with one source row -- fetch_state
  references it, and get-connection enforces that."
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
  write."
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
  afterwards. handler-fn is called once per request with the HttpExchange, and
  the [status body headers] it returns becomes the response."
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

(defn- get-fetch-state! [url]
  (with-open [conn (db/get-connection *db-spec*)]
    (jdbc/execute-one! conn
                       ["SELECT * FROM fetch_state WHERE url = ?" url]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- fetch!
  [url & {:as opts}]
  (with-open [conn (db/get-connection *db-spec*)]
    (fetch/fetch-url! conn (merge {:url url :source-id *source-id*} opts))))

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

(deftest the-delay-is-waited-out-before-the-request
  (with-server
    (fn [_] [200 "hello" {}])
    (fn [url]
      (let [started (System/currentTimeMillis)]
        (fetch! url :delay-ms 100)
        (is (>= (- (System/currentTimeMillis) started) 100))))))
