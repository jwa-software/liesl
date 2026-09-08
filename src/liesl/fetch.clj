;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.fetch
  "Fetches one URL conditionally and keeps its fetch_state row honest.

  Which URLs a source has, and what to do with a body once it arrives, are
  someone else's problem. This namespace does one request."
  (:require [clojure.edn          :as edn]
            [clojure.java.io      :as io]
            [clojure.string       :as str]
            [liesl.version        :as version]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.io       File InputStream]
           [java.net      URI]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers]
           [java.nio.file CopyOption Files StandardCopyOption]
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

(defn- sha-256
  "Digest of a stream, read in chunks so a large file never has to fit in memory.

  in  the stream, read to its end; not closed here

  Returns the 32 SHA-256 bytes."
  ^bytes [^InputStream in]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (loop [n (.read in buffer)]
      (when (pos? n)
        (.update digest buffer 0 n)
        (recur (.read in buffer))))
    (.digest digest)))

(defn- last-fetch-state!
  "The validators the last fetch of this URL left behind.

  conn  an open connection
  url   the fetch_state key

  Returns {:etag ... :last_modified ...}, either value possibly nil, or nil
  when the URL has never been fetched."
  [conn url]
  (jdbc/execute-one! conn
                     ["SELECT etag, last_modified FROM fetch_state WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn- build-request
  "A GET carrying whatever validators the last fetch left behind. With neither,
  it is an ordinary request and the server has no way to answer 304.

  url            what to fetch
  etag           sent as If-None-Match, or nil
  last_modified  sent as If-Modified-Since, or nil

  Returns the HttpRequest."
  ^HttpRequest [url {:keys [etag last_modified]}]
  (let [^HttpRequest$Builder builder
        (doto
         (-> url URI/create HttpRequest/newBuilder)
          (.header "User-Agent" user-agent)
          (.timeout request-timeout))]
    (when etag          (.header builder "If-None-Match"     etag))
    (when last_modified (.header builder "If-Modified-Since" last_modified))
    (.build builder)))

(defn- header
  "One response header.

  response  the HttpResponse
  name      the header name; case does not matter

  Returns its first value, or nil when absent."
  ^String [^HttpResponse response ^String name]
  (-> response .headers (.firstValue name) (.orElse nil)))

(defn- upsert!
  "Write the row back. next_fetch is the caller's decision, not ours -- how soon
  a URL is worth revisiting depends on what it is.

  conn           an open connection
  url            the fetch_state key
  source-id      the source row it belongs to
  next-fetch     written as given
  status         the HTTP status of this fetch
  etag           the previous value is kept when nil
  last-modified  the previous value is kept when nil
  content-hash   the previous value is kept when nil
  failed?        true adds one consecutive failure, false resets the count

  Returns nothing the caller needs."
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

(defn- fetch-to-string!
  "Send the request and keep the response body in memory.

  request  the HttpRequest to send

  Returns {:response :status :body :content-hash}; :content-hash is nil unless
  the status is 200."
  [^HttpRequest request]
  (let [^HttpClient   http     @client
        ^HttpResponse response (.send http request (HttpResponse$BodyHandlers/ofString))
        ^String       body     (.body response)
        status                 (.statusCode response)]
    {:response     response
     :status       status
     :body         body
     :content-hash (when (= 200 status) (sha-256 (io/input-stream (.getBytes body "UTF-8"))))}))

(defn- fetch-to-file!
  "Send the request and write the response body into target. The body is
  downloaded into a temp file beside target and moved into place only on 200.

  request  the HttpRequest to send
  target   the file to end up with; missing parent directories are created

  Returns {:response :status :file :content-hash}; :file is target made
  absolute, :content-hash is nil unless the status is 200."
  [^HttpRequest request ^File target]
  (let [target (.getAbsoluteFile target)]
    (io/make-parents target)
    (let [prefix     (str (.getName target) ".")
          suffix     ".part"
          dir        (.getParentFile target)
          ^File temp (File/createTempFile prefix suffix dir)]
      (try
        (let [^HttpClient   http         @client
              ^HttpResponse response     (.send http request (HttpResponse$BodyHandlers/ofFile (.toPath temp)))
              status                     (.statusCode response)
              ;; Only 200 OK brings new content worth keeping. 304 Not Modified
              ;; and any failure alike leave the previous download in place.
              ok?                        (= 200 status)
              content-hash               (when ok? (with-open [in (io/input-stream temp)] (sha-256 in)))]
          (when ok?
            (Files/move (.toPath temp) (.toPath target) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))
          {:response     response
           :status       status
           :file         target
           :content-hash content-hash})
        ;; After a move the temp path no longer exists and delete does nothing;
        ;; on every other path, including an exception mid-download, it cleans up.
        (finally (.delete temp))))))

(defn fetch-url!
  "Fetch one URL, conditionally, and update its fetch_state row.

  url         what to fetch
  source-id   the source row it belongs to
  next-fetch  written to fetch_state.next_fetch; the caller's decision
  as          where the response body goes, :string or :file
  opts        what that choice needs: nothing for :string, {:file target} for :file

  Returns
  {:status 200 :body \"...\"} new content, as :string
  {:status 200 :file f}       new content, written to f, as :file
  {:status 304}               not modified since the last fetch
  {:status n}                 anything else; an HTTP status never throws

  A network failure throws."
  [conn {:keys [url source-id next-fetch as opts]}]
  (let [request  (build-request url (last-fetch-state! conn url))
        received (case as
                   :string (fetch-to-string! request)
                   :file   (fetch-to-file!   request (io/file (:file opts)))
                   (throw (ex-info "Unknown :as" {:as as :allowed #{:string :file}})))
        {:keys [response status content-hash]} received
        ;; Only 200 OK carries content to return. 304 Not Modified and any
        ;; failure alike come back as the status alone; which statuses count
        ;; as failures is :failed? below.
        ok?      (= 200 status)]
    (upsert! conn {:url           url
                   :source-id     source-id
                   :next-fetch    next-fetch
                   :status        status
                   :etag          (header response "etag")
                   :last-modified (header response "last-modified")
                   :content-hash  content-hash
                   :failed?       (>= status 400)})
    (if ok?
      ;; Whichever of :body and :file the handler produced; the other is absent.
      (select-keys received [:status :body :file])
      {:status status})))

(defn fetch-archive!
  "Fetch one version of a source's archive into dir, conditionally.

  conn      an open connection
  source    a source row, as upsert-sources! returns it
  version   which version, e.g. \"R4\"; replaces {version} in the config's path
  dir       the directory all archives live under

  Returns what fetch-url! returns; on 200 the :file is
  dir/<corpus>/<source name>/<version>/<archive>."
  [conn {:keys [source version dir]}]
  (let [{:keys [version-path archive]} (some-> (:config source) edn/read-string)]
    (when-not version-path (throw (ex-info (format "%s config needs :version-path" (:name source)) {})))
    (when-not archive      (throw (ex-info (format "%s config needs :archive"      (:name source)) {})))
    (let [version-path' (str/replace version-path       "{version}"      version)
          url           (str         (:base_url source) version-path'    archive)
          file          (io/file     dir                (:corpus source) (:name source) version archive)]
      (fetch-url! conn {:url       url
                        :source-id (:id source)
                        :as        :file
                        :opts      {:file file}}))))
