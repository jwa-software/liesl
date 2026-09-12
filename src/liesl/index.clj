;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.index
  "Keeps the Lucene index level with the document table. The table is the
  truth and the index is derived from it: a row whose `indexed_at` is null is
  waiting to be written, and writing it sets the column."
  (:require [clojure.java.io      :as io]
            [liesl.db             :as db]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.io                             File]
           [java.time                           Instant]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.document          Document Field$Store StoredField StringField TextField]
           [org.apache.lucene.index             IndexWriter IndexWriterConfig Term]
           [org.apache.lucene.store             FSDirectory]))

;; Under the data directory, beside the database and the archives.
(def ^:private index-dir-name "index")

;; ---- Pure helpers: no I/O ----

(defn- document->lucene
  "One document row as the Lucene document that stands for it.

  row  {:id :url :kind :version :title :body}, as `unindexed!` reads it

  Returns a `Document`: `url`, `kind` and `version` as exact-match fields,
  `title` and `body` analysed for search, every field stored so a hit can be
  shown without the database. A nil `title` or `version` is left out."
  ^Document [{:keys [id url kind version title body]}]
  (let [doc (Document.)]
       (.add doc (StoredField. "id"   (long id)))
       (.add doc (StringField. "url"  ^String url  Field$Store/YES))
       (.add doc (StringField. "kind" ^String kind Field$Store/YES))
       (when version (.add doc (StringField. "version" ^String version Field$Store/YES)))
       (when title   (.add doc (TextField.   "title"   ^String title   Field$Store/YES)))
       (.add doc (TextField. "body" ^String body Field$Store/YES))
       doc))

;; ---- Database ----

(defn- unindexed!
  "The rows waiting to be indexed.

  conn  an open connection

  Returns a vector of {:id :url :kind :version :title :body}, lowest id first."
  [conn]
  (jdbc/execute! conn
                 [(str "SELECT id, url, kind, version, title, body "
                       "FROM document "
                       "WHERE indexed_at IS NULL "
                       "ORDER BY id")]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- mark-as-indexed!
  "Record that rows are in the index, all in one transaction.

  conn  an open connection
  ids   the row ids

  Returns nothing the caller needs."
  [conn ids]
  (let [now (str (Instant/now))]
       (jdbc/with-transaction [tx conn]
         (doseq [id ids]
           (jdbc/execute-one! tx
                              ["UPDATE document SET indexed_at = ? WHERE id = ?" now id])))))

;; ---- Public, each built on the one above: where the index lives, open and
;; ---- close, the unindexed rows, the command ----

(defn index-dir
  "The directory the index lives in: `data/index`.

  Returns it as a `File`."
  ^File []
  (io/file (db/data-dir) index-dir-name))

(defn open
  "Open the index for writing, creating it when there is none. Holds Lucene's
  write lock until close, so one writer per index at a time.

  dir  the index directory, created if missing

  Returns {:directory <the FSDirectory> :writer <the IndexWriter>}, for `close`."
  [dir]
  (let [directory (FSDirectory/open (.toPath (io/file dir)))
        writer    (IndexWriter. directory (IndexWriterConfig. (StandardAnalyzer.)))]
       {:directory directory :writer writer}))

(defn close
  "Close what `open` returned, committing what is pending and releasing the
  write lock.

  opened  what `open` returned

  Returns nil."
  [{:keys [^IndexWriter writer ^FSDirectory directory]}]
  (.close writer)
  (.close directory))

(defn index-unindexed!
  "Write every row whose `indexed_at` is null into the index and mark it. A
  row goes in with `updateDocument` on its `url`, so a changed row replaces
  its earlier copy instead of joining it.

  conn    an open connection
  opened  what `open` returned

  Returns how many rows were written. The marking comes after Lucene's
  `commit`, so a failure between the two leaves the rows on the queue rather
  than off it."
  [conn {:keys [^IndexWriter writer]}]
  (let [rows (unindexed! conn)]
       (doseq [row rows]
         (.updateDocument writer
                          (Term. "url" ^String (:url row))
                          (document->lucene row)))
       (.commit writer)
       (mark-as-indexed! conn (map :id rows))
       (count rows)))

(defn ^:exec-fn index
  "Bring the index under `data/index` level with the `document` table.

  Prints how many rows were written. Returns nil, because `-X` discards it."
  [_]
  (with-open [conn (db/get-connection)]
    (let [opened (open (index-dir))]
         (try
           (println (index-unindexed! conn opened) "documents indexed")
           (finally
             (close opened))))))
