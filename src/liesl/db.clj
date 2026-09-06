;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.db
  "The one place that knows where the database is and how a connection to it
  must be configured.

  Two pragmas matter, and they differ in how long they last:

  - `journal_mode = WAL` is *persistent*: set once, it stays in the file. It
    lets one writer and many readers coexist, which a long fetch needs.

  - `foreign_keys` is *per connection* and off by default. Until a connection
    sets it, every REFERENCES clause is ignored and bad rows land silently.

  So WAL is applied once, when migrating, and foreign keys are applied by
  `get-connection` -- which is why code should open connections through here
  rather than calling next.jdbc directly."
  (:require [clojure.java.io :as io]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(def ^:private default-db-file-name "liesl.db")

(def ^:private migration-dir
  "Resolved on the classpath, so it must match a directory under one of the
  :paths entries in deps.edn."
  "migrations")

(defn- project-root
  "The working directory, asserted to be a tools.deps project root."
  ^java.io.File []
  (let [dir  (io/file (System/getProperty "user.dir"))
        deps (io/file dir "deps.edn")]
    (when-not (.isFile deps)
      (throw (ex-info "Not a tools.deps project root" {:dir (str dir)})))
    dir))

(defn- set-wal!
  "Switch the database to WAL mode."
  [spec]
  (with-open [conn (jdbc/get-connection spec)]
    (jdbc/execute-one! conn ["PRAGMA journal_mode = WAL"])))

(defn default-db-file
  "Where the database lives when nothing says otherwise.

  LIESL_DB overrides it, which is how a user keeps their corpus off the disk
  the clone is on. Otherwise the database sits beside deps.edn."
  ^java.io.File []
  (if-let [db-file (System/getenv "LIESL_DB")]
    (io/file db-file)
    (io/file (project-root) default-db-file-name)))

(defn db-spec
  "A next.jdbc db-spec for the given database file, or for the default one."
  ([] (db-spec (default-db-file)))
  ([file] {:dbtype "sqlite" :dbname (str file)}))

(defn get-connection
  "Open a connection with the per-connection pragmas applied."
  (^java.sql.Connection [] (get-connection (db-spec)))
  (^java.sql.Connection [spec]
   (doto (jdbc/get-connection spec) (jdbc/execute-one! ["PRAGMA foreign_keys = ON"]))))

(defn migration-config
  "Migratus configuration. The database location is defined once, here, rather
  than repeated in deps.edn -- the :migrate alias calls into this namespace
  precisely so the two cannot drift apart."
  ([] (migration-config (db-spec)))
  ([spec] {:store :database
           :migration-dir migration-dir
           :db spec}))

(defn migrate
  "Apply every pending migration. `clj -X:migrate`. Takes the exec map because
  that is what -X passes; :db-file selects a non-default database."
  [{:keys [db-file]}]
  (let [spec (db-spec (or db-file (default-db-file)))]
    (set-wal! spec)
    (migratus/migrate (migration-config spec))))

(defn rollback
  "Undo the most recently applied migration. `clj -X:rollback`."
  [{:keys [db-file]}]
  (migratus/rollback (migration-config (db-spec (or db-file (default-db-file))))))

(defn pending-list
  "Print the migrations that have not been applied. `clj -X:pending-list`.
  Prints rather than returns, because -X discards the return value."
  [{:keys [db-file]}]
  (let [spec (db-spec (or db-file (default-db-file)))]
    (doseq [m (migratus/pending-list (migration-config spec))]
      (println m))))
