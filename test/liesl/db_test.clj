;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.db-test
  "These tests guard decisions, not SQLite's own behaviour. Each one fails if
  something we chose is quietly undone: the pragma that makes foreign keys
  real, the STRICT clauses that make column types real, the deliberate
  absence of a foreign key on judgment, and a rollback that actually works."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [liesl.db :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private ^:dynamic *db-file* nil)
(def ^:private ^:dynamic *db-spec* nil)

(defn- query!
  "Read rows, with plain lower-case keys."
  [conn sql]
  ;; Without :builder-fn the keys arrive qualified by their table:
  ;;   {:sqlite_master/name "source"}  ->  {:name "source"}
  (jdbc/execute! conn [sql] {:builder-fn rs/as-unqualified-lower-maps}))

(defn- exec!
  "Run a statement whose rows are not read -- an insert, or one meant to throw."
  [conn sql]
  (jdbc/execute-one! conn [sql]))

(defn- with-temp-db
  "A fresh migrated database per test. WAL leaves -wal and -shm files beside
  it, so all three are deleted afterwards."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-test-" ".db")
        spec     (db/db-spec file)
        silently true]
    (try
      (binding [*db-file* file
                *db-spec* spec]
        (db/migrate {:db-file file})
        (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (io/delete-file (io/file (str file suffix)) silently))))))

(defn- table-names
  "User tables as a set of names -- SQLite's own sqlite_* tables excluded, and
  a set because table order is arbitrary."
  ^clojure.lang.IPersistentSet [conn]
  (into #{}
        (map :name)
        (query! conn
                (str "SELECT name FROM sqlite_master "
                     "WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"))))

(use-fixtures :each with-temp-db)

(deftest migration-creates-every-table
  (with-open [conn (db/get-connection *db-spec*)]
    (is (= #{"source" "fetch_state" "document" "link" "judgment"
             ;; migratus keeps its own bookkeeping here; it is not part of
             ;; the data model but it is part of the database.
             "schema_migrations"}
           (table-names conn)))))

(deftest every-data-table-is-strict
  ;; A future migration that forgets STRICT would leave column types
  ;; unenforced for that table alone, silently.
  (with-open [conn (db/get-connection *db-spec*)]
    (is (= #{"source" "fetch_state" "document" "link" "judgment"}
           (into #{}
                 (map :name)
                 (query! conn
                         (str "SELECT name FROM pragma_table_list "
                              "WHERE schema = 'main' AND strict = 1")))))))

(deftest get-connection-enforces-foreign-keys
  (with-open [conn (db/get-connection *db-spec*)]
    (is (thrown? java.sql.SQLException
                 (exec! conn "INSERT INTO fetch_state (url, source_id) VALUES ('u', 999)")))))

(deftest a-raw-connection-does-not-enforce-foreign-keys
  ;; The reason liesl.db/get-connection exists. If this ever starts failing,
  ;; SQLite changed its default and the pragma is no longer load-bearing --
  ;; which is worth knowing, hence a test rather than a comment.
  (with-open [conn (jdbc/get-connection *db-spec*)]
    (exec! conn "INSERT INTO fetch_state (url, source_id) VALUES ('u', 999)")
    (is (= 1 (:c (first (query! conn "SELECT COUNT(*) AS c FROM fetch_state")))))))

(deftest judgment-accepts-a-document-this-installation-has-not-fetched
  ;; The published judgment set covers documents a narrow date range never
  ;; downloaded. A foreign key to document would reject exactly those.
  (with-open [conn (db/get-connection *db-spec*)]
    (exec! conn (str "INSERT INTO judgment VALUES "
                     "('postgresql', 'why was HOT added', 'https://example.invalid/never-fetched', "
                     "3, 'jwa', '2026-09-05T00:00:00Z')"))
    (is (= 1 (:c (first (query! conn "SELECT COUNT(*) AS c FROM judgment")))))))

(deftest grade-outside-the-scale-is-rejected
  (with-open [conn (db/get-connection *db-spec*)]
    (is (thrown? java.sql.SQLException
                 (exec! conn
                        (str "INSERT INTO judgment VALUES "
                             "('postgresql', 'q', 'https://example.invalid/x', "
                             "4, 'jwa', '2026-09-05T00:00:00Z')"))))))

(deftest migrating-switches-the-database-to-wal
  (with-open [conn (db/get-connection *db-spec*)]
    (is (= "wal"
           (:journal_mode (first (query! conn "PRAGMA journal_mode")))))))

(deftest rollback-removes-the-data-model
  (db/rollback {:db-file *db-file*})
  (with-open [conn (db/get-connection *db-spec*)]
    (is (= #{"schema_migrations"} (table-names conn)))))

(deftest pending-list-reports-what-has-not-run
  ;; The fixture already migrated, so there is nothing pending yet. Migratus
  ;; logs to stderr, so with-out-str sees only what pending-list prints.
  (is (str/blank? (with-out-str (db/pending-list {:db-file *db-file*}))))
  (db/rollback {:db-file *db-file*})
  (is (str/includes?
       (with-out-str (db/pending-list {:db-file *db-file*}))
       "initial-schema")))
