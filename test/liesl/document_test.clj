;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.document-test
  "Against a fresh migrated database each time, with one source row for the
  documents to belong to."
  (:require [clojure.java.io      :as io]
            [clojure.test         :refer [deftest is]]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [liesl.document       :as document]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private one-source
  {:corpus  "test"
   :sources [{:name "pages" :kind "spec" :base-url "http://127.0.0.1/"}]})

;; A document in the shape a parser hands over.
(def ^:private patient
  {:url     "http://127.0.0.1/patient.html"
   :kind    "spec"
   :title   "Resource Patient - Content"
   :body    "Demographics and other administrative information."
   :version "R4"})

(def ^:private indexed-at "2026-09-12T00:00:00Z")

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-document-test-" ".db")
        silently true]
       (try
         (db/migrate {:db-file file})
         (with-open [conn (db/get-connection (db/db-spec file))]
           (f conn))
         (finally
           (doseq [suffix ["" "-wal" "-shm"]
                   :let   [target (io/file (str file suffix))]]
             (io/delete-file target silently))))))

(defn- insert-a-source!
  "The one source row the documents belong to.

  conn  an open connection

  Returns its id."
  [conn]
  (:id (first (corpus/upsert-sources! conn one-source))))

(defn- mark-indexed!
  "Pretend the indexer has seen a row.

  conn  an open connection
  id    the document's row id

  Returns nothing the tests need."
  [conn id]
  (jdbc/execute-one! conn ["UPDATE document SET indexed_at = ? WHERE id = ?" indexed-at id]))

(defn- select-document!
  "One document row as stored.

  conn  an open connection
  url   which one

  Returns {:id :title :body :version :indexed_at}, or nil."
  [conn url]
  (jdbc/execute-one! conn
                     ["SELECT id, title, body, version, indexed_at FROM document WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(deftest a-document-is-inserted-with-its-hash
  (with-temp-db
    (fn [conn]
        (let [source-id (insert-a-source! conn)
              row       (document/upsert! conn source-id patient)]
             (is (integer? (:id row)) "the row id comes back")
             (is (= [source-id "spec" (:url patient) (:title patient) (:version patient) nil]
                    ((juxt :source_id :kind :url :title :version :indexed_at) row))
                 "the columns hold what was given, and nothing has indexed the row")
             (is (= 32 (count (:content_hash row))) "a SHA-256, 32 bytes")))))

(deftest a-second-upsert-of-the-same-url-keeps-the-id
  (with-temp-db
    (fn [conn]
        (let [source-id (insert-a-source! conn)
              first-id  (:id (document/upsert! conn source-id patient))
              second-id (:id (document/upsert! conn source-id (assoc patient :body "Rewritten.")))]
             (is (= first-id second-id) "the URL is the identity, so the id survives")
             (is (= "Rewritten." (:body (select-document! conn (:url patient))))
                 "the body is the new one")))))

(deftest an-unchanged-document-stays-indexed-and-a-changed-one-does-not
  (with-temp-db
    (fn [conn]
        (let [source-id (insert-a-source! conn)
              id        (:id (document/upsert! conn source-id patient))]
             (mark-indexed! conn id)
             (is (= indexed-at (:indexed_at (document/upsert! conn source-id patient)))
                 "the same title and body leave indexed_at alone")
             (is (nil? (:indexed_at (document/upsert! conn source-id (assoc patient :body "Rewritten."))))
                 "a changed body clears it")
             (mark-indexed! conn id)
             (is (nil? (:indexed_at (document/upsert! conn source-id (assoc patient :body "Rewritten." :title "Renamed"))))
                 "a changed title clears it too: the title is indexed as well")))))
