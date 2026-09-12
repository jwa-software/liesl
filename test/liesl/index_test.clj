;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.index-test
  "Against a fresh migrated database and a temp index directory, with rows
  written through liesl.document/upsert! so indexed_at behaves as it does in
  use. The index is read back through Lucene's own reader."
  (:require [clojure.java.io      :as io]
            [clojure.test         :refer [deftest is]]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [liesl.document       :as document]
            [liesl.index          :as index]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.file           Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.lucene.index DirectoryReader]
           [org.apache.lucene.search IndexSearcher MatchAllDocsQuery ScoreDoc]
           [org.apache.lucene.store FSDirectory]))

(def ^:private one-source
  {:corpus  "test"
   :sources [{:name "pages" :kind "spec" :base-url "http://127.0.0.1/"}]})

(def ^:private patient
  {:url "http://127.0.0.1/patient.html" :kind "spec" :title "Resource Patient - Content" :body "Demographics." :version "R4"})

(def ^:private overview
  {:url "http://127.0.0.1/overview.html" :kind "spec" :title "FHIR Overview" :body "Welcome." :version "R4"})

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-index-test-" ".db")
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
  (let [dir      (.toFile (Files/createTempDirectory "liesl-index-test-" (make-array FileAttribute 0)))
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

(defn- insert-documents!
  "The source row and the given documents, written as upsert! writes them.

  conn       an open connection
  documents  document maps

  Returns nothing the tests need."
  [conn documents]
  (let [source-id (:id (first (corpus/upsert-sources! conn one-source)))]
       (doseq [d documents]
         (document/upsert! conn source-id d))))

(defn- index!
  "One run of index-unindexed!, with the index opened and closed around it.

  conn  an open connection
  dir   the index directory

  Returns how many rows were written."
  [conn dir]
  (let [opened (index/open dir)]
       (try
         (index/index-unindexed! conn opened)
         (finally
           (index/close opened)))))

(defn- read-index
  "Every live document in the index, by URL.

  dir  the index directory

  Returns {<url> {:title <string> :body <string> :version <string>} ...}."
  [dir]
  (with-open [directory (FSDirectory/open (.toPath (io/file dir)))
              reader    (DirectoryReader/open directory)]
    (let [searcher (IndexSearcher. reader)
          fields   (.storedFields reader)
          hits     (.-scoreDocs (.search searcher (MatchAllDocsQuery.) 100))]
         (into {}
               (map (fn [^ScoreDoc hit]
                        (let [doc (.document fields (.-doc hit))]
                             [(.get doc "url") {:title (.get doc "title") :body (.get doc "body") :version (.get doc "version")}])))
               hits))))

(defn- indexed-at!
  "Each row's indexed_at, by URL.

  conn  an open connection

  Returns {<url> <string or nil> ...}."
  [conn]
  (into {}
        (map (juxt :url :indexed_at))
        (jdbc/execute! conn ["SELECT url, indexed_at FROM document"] {:builder-fn rs/as-unqualified-lower-maps})))

(deftest unindexed-rows-are-indexed-and-marked
  (with-db-and-dir
    (fn [conn dir]
        (insert-documents! conn [patient overview])
        (is (= 2 (index! conn dir)) "both rows were waiting")
        (is (= {(:url patient)  {:title (:title patient)  :body (:body patient)  :version "R4"}
                (:url overview) {:title (:title overview) :body (:body overview) :version "R4"}}
               (read-index dir))
            "each row is one document with its fields stored")
        (is (every? some? (vals (indexed-at! conn))) "and each row is marked"))))

(deftest a-second-run-writes-nothing
  (with-db-and-dir
    (fn [conn dir]
        (insert-documents! conn [patient overview])
        (index! conn dir)
        (is (= 0 (index! conn dir)) "nothing is waiting any more"))))

(deftest a-changed-row-replaces-its-copy
  (with-db-and-dir
    (fn [conn dir]
        (insert-documents! conn [patient overview])
        (index! conn dir)
        (insert-documents! conn [(assoc patient :body "Rewritten.")])
        (is (= 1 (index! conn dir)) "the upsert cleared indexed_at on the changed row only")
        (let [documents (read-index dir)]
             (is (= 2 (count documents))                            "still one document per URL")
             (is (= "Rewritten." (:body (get documents (:url patient)))) "and it is the new body")))))
