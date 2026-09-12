;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.parse-test
  "Against a fresh migrated database and a temp archives directory. The parser
  the sources name is defined here, so the engine is tested without any
  corpus package; the archives are empty files, because only their presence
  is checked."
  (:require [clojure.java.io      :as io]
            [clojure.string       :as str]
            [clojure.test         :refer [deftest is]]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [liesl.parse          :as parse]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.file           Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private base-url "http://127.0.0.1/")

;; One source naming a parser, one that does not. The parser is a quoted
;; symbol: this map is Clojure, not EDN, and would otherwise be evaluated.
(def ^:private definition
  {:corpus   "test"
   :versions ["v1" "v2"]
   :sources  [{:name     "pages"
               :kind     "spec"
               :base-url base-url
               :config   {:version-path "{version}/"
                          :archive      "pages.zip"
                          :parser       'liesl.parse-test/two-pages}}
              {:name     "repo"
               :kind     "source"
               :base-url "http://127.0.0.1/repo.git"}]})

(defn- two-pages
  "The parser the pages source names: two documents per version.

  archive-file  not read; parse-versions! only checks that it exists
  version       goes into the body, so a row shows which version wrote it
  url-prefix    what the URLs start with

  Returns two document maps."
  [archive-file version url-prefix]
  [{:url (str url-prefix "a.html") :title "A" :body (str "A in " version)}
   {:url (str url-prefix "b.html") :title "B" :body (str "B in " version)}])

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-parse-test-" ".db")
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
  "A fresh archives directory, removed with its contents afterwards.

  f  the test body, given the directory as a File

  Returns what f returns."
  [f]
  (let [dir      (.toFile (Files/createTempDirectory "liesl-parse-test-" (make-array FileAttribute 0)))
        silently true]
       (try
         (f dir)
         (finally
           ;; file-seq lists a directory before its contents; reversed, children go first.
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file silently))))))

(defn- with-db-and-dir
  "Both of the above.

  f  the test body, given the open connection and the directory

  Returns what f returns."
  [f]
  (with-temp-db (fn [conn] (with-temp-dir (fn [dir] (f conn dir))))))

(defn- touch-archives!
  "Empty files where fetch-archive! would have put the pages source's archives.

  dir       the archives directory
  versions  which versions to fake

  Returns nothing the tests need."
  [dir versions]
  (doseq [version versions
          :let    [file (io/file dir "test" "pages" version "pages.zip")]]
    (io/make-parents file)
    (spit file "")))

(defn- pages-source!
  "The definition's source rows written, and the one naming a parser picked out.

  conn  an open connection

  Returns the pages source row."
  [conn]
  (first (corpus/upsert-sources! conn definition)))

(defn- select-documents!
  "Every document row, by URL.

  conn  an open connection

  Returns a vector of {:id :url :kind :version :body}."
  [conn]
  (jdbc/execute! conn
                 ["SELECT id, url, kind, version, body FROM document ORDER BY url"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(deftest each-version-is-parsed-into-rows
  (with-db-and-dir
    (fn [conn dir]
        (touch-archives! dir ["v1" "v2"])
        (let [source (pages-source! conn)
              result (parse/parse-versions! conn {:source source :versions ["v1" "v2"] :dir dir})
              rows   (select-documents! conn)]
             (is (= [{:version "v1" :documents 2} {:version "v2" :documents 2}] result) "two per version, in order")
             (is (= [(str base-url "v1/a.html") (str base-url "v1/b.html") (str base-url "v2/a.html") (str base-url "v2/b.html")]
                    (map :url rows))
                 "the URLs start with the version's prefix")
             (is (= #{["spec" "v1" "A in v1"] ["spec" "v2" "B in v2"]}
                    (set (map (juxt :kind :version :body) (filter #(#{"A in v1" "B in v2"} (:body %)) rows))))
                 "kind comes from the source, version from the loop")))))

(deftest a-second-run-keeps-the-ids
  (with-db-and-dir
    (fn [conn dir]
        (touch-archives! dir ["v1"])
        (let [source (pages-source! conn)
              run!   #(do (parse/parse-versions! conn {:source source :versions ["v1"] :dir dir})
                          (map :id (select-documents! conn)))
              first-ids  (run!)
              second-ids (run!)]
             (is (= 2 (count first-ids)) "two rows the first time")
             (is (= first-ids second-ids)  "the same two rows the second time")))))

(deftest a-version-not-fetched-is-refused
  (with-db-and-dir
    (fn [conn dir]
        (let [source  (pages-source! conn)
              message (try (parse/parse-versions! conn {:source source :versions ["v1"] :dir dir}) nil
                           (catch clojure.lang.ExceptionInfo e (ex-message e)))]
             (is (str/starts-with? (str message) "pages v1 has not been fetched: no ")
                 "the error names the source, the version and the missing file")
             (is (empty? (select-documents! conn)) "and nothing was written")))))

(deftest a-parser-that-does-not-exist-is-refused
  (with-db-and-dir
    (fn [conn dir]
        (touch-archives! dir ["v1"])
        (let [source  (pages-source! conn)
              source  (assoc source :config (pr-str {:version-path "{version}/" :archive "pages.zip" :parser 'liesl.parse-test/no-such-parser}))
              message (try (parse/parse-versions! conn {:source source :versions ["v1"] :dir dir}) nil
                           (catch clojure.lang.ExceptionInfo e (ex-message e)))]
             (is (= "pages config names a parser that does not exist: liesl.parse-test/no-such-parser" message)
                 "the error names the source and the symbol")))))

(deftest a-corpus-parses-the-sources-naming-a-parser-and-skips-the-rest
  (with-db-and-dir
    (fn [conn dir]
        (touch-archives! dir ["v1" "v2"])
        (let [result (parse/parse-corpus! conn {:definition definition :dir dir})]
             (is (= {"pages" [{:version "v1" :documents 2} {:version "v2" :documents 2}]} result)
                 "the repo source has no parser and is not in the result; the versions are the definition's")
             (is (= 4 (count (select-documents! conn))) "four rows, all from the pages source")))))

(deftest versions-given-to-parse-corpus-override-the-definition
  (with-db-and-dir
    (fn [conn dir]
        (touch-archives! dir ["v2"])
        (is (= {"pages" [{:version "v2" :documents 2}]}
               (parse/parse-corpus! conn {:definition definition :versions ["v2"] :dir dir}))
            "only v2 is parsed, so the missing v1 archive is never looked for"))))
