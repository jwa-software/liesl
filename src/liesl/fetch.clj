;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.fetch
  "Fetches one URL conditionally and keeps its fetch_state row honest.

  Which URLs a source has, and what to do with a body once it arrives, are
  someone else's problem. This namespace does one request."
  (:require [liesl.version        :as version]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.net      URI]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time     Duration Instant]))

(def ^:private user-agent
  (str "liesl/" version/version " (+https://github.com/jwa-software/liesl)"))

(def ^:private request-timeout (Duration/ofSeconds 30))

;; An HttpClient owns a thread pool, and a plain def would create it the moment
;; this namespace is required. delay is lazy evaluation, not a duration: the
;; body runs at the first @client below, once, and every later @ gets that same
;; client back.
(def ^:private client (delay (HttpClient/newHttpClient)))

(defn- sha-256 ^bytes [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))))

(defn- prev-state
  "The row for this URL, or nil if it has never been fetched."
  [conn url]
  (jdbc/execute-one! conn
                     ["SELECT etag, last_modified FROM fetch_state WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn- build-request
  "A GET carrying whatever validators the last fetch left behind. With neither,
  it is an ordinary request and the server has no way to answer 304."
  ^HttpRequest [url {:keys [etag last_modified]}]
  (let [^HttpRequest$Builder builder
        (doto
         (-> url URI/create HttpRequest/newBuilder)
          (.header "User-Agent" user-agent)
          (.timeout request-timeout))]
    (when etag          (.header builder "If-None-Match"     etag))
    (when last_modified (.header builder "If-Modified-Since" last_modified))
    (.build builder)))

(defn- header ^String [^HttpResponse response ^String name]
  (-> response
      .headers
      (.firstValue name)
      (.orElse nil)))

(defn- upsert!
  "Write the row back. next_fetch is the caller's decision, not ours -- how soon
  a URL is worth revisiting depends on what it is."
  [conn {:keys [url source-id next-fetch status etag last-modified content-hash failed?]}]
  (jdbc/execute-one!
   conn
   [(str "INSERT INTO fetch_state "
         "  (url, source_id, etag, last_modified, content_hash, last_fetched, next_fetch, status, failures) "
         "  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
         "ON CONFLICT (url) DO UPDATE SET "
         "  etag          = COALESCE(excluded.etag, fetch_state.etag), "
         "  last_modified = COALESCE(excluded.last_modified, fetch_state.last_modified), "
         "  content_hash  = COALESCE(excluded.content_hash, fetch_state.content_hash), "
         "  last_fetched  = excluded.last_fetched, "
         "  next_fetch    = excluded.next_fetch, "
         "  status        = excluded.status, "
         "  failures      = CASE WHEN excluded.failures > 0 "
         "                       THEN fetch_state.failures + 1 ELSE 0 END")
    url source-id etag last-modified content-hash (str (Instant/now)) next-fetch status (if failed? 1 0)]))

(defn fetch-url!
  "Fetch one URL, conditionally, and update its fetch_state row.

  Returns {:status 200 :body \"...\"} when the content is new, {:status 304}
  when the server says it has not changed, or {:status n} for anything else.
  A network failure throws; an HTTP status never does."
  [conn {:keys [url source-id delay-ms next-fetch]}]
  ;; Sleep before the request, not after, so a caller cannot skip it by
  ;; abandoning the loop.
  (when (pos? (or delay-ms 0)) (Thread/sleep (long delay-ms)))
  (let [^HttpRequest  request  (build-request url (prev-state conn url))
        ^HttpClient   http     @client
        ^HttpResponse response (.send       http request (HttpResponse$BodyHandlers/ofString))
        ^String       body     (.body       response)
        status                 (.statusCode response)
        changed?               (= 200 status)]
    (upsert! conn {:url           url
                   :source-id     source-id
                   :next-fetch    next-fetch
                   :status        status
                   :etag          (header response "etag")
                   :last-modified (header response "last-modified")
                   :content-hash  (when changed? (sha-256 body))
                   :failed?       (>= status 400)})
    (if changed?
      {:status status :body body}
      {:status status})))
