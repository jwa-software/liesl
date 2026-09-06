;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.corpus-test
  "The fixtures live at the test-path root, because a corpus is addressed by
  its directory name on the classpath."
  (:require [clojure.edn          :as edn]
            [clojure.java.io      :as io]
            [clojure.test         :refer [deftest is]]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]))

;; A definition in the shape corpus/load returns, so the tests do not depend on
;; what corpora/fhir/corpus.edn happens to say.
(def ^:private two-sources
  {:corpus  "test"
   :fetch   {:delay-ms 0}
   :sources [{:name "pages" :kind "spec"   :base-url "http://127.0.0.1/" :config {:clone :shallow}}
             {:name "repo"  :kind "source" :base-url "http://127.0.0.1/repo.git"}]})

(defn- succeed?
  "Invoke f and report whether it returned."
  [f]
  (try (f) true (catch clojure.lang.ExceptionInfo _ false)))

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards. A function
  rather than a fixture, because only the seeding tests need one."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-corpus-test-" ".db")
        silently true]
    (try
      (db/migrate {:db-file file})
      (with-open [conn (db/get-connection (db/db-spec file))]
        (f conn))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]
                :let   [target (io/file (str file suffix))]]
          (io/delete-file target silently))))))

(defn- select-sources!
  [conn]
  (jdbc/execute! conn
                 ["SELECT id, name, base_url, config FROM source ORDER BY id"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(deftest loads-the-fhir-corpus
  (let [loaded (corpus/load "fhir")]
    (is (= "fhir" (:corpus loaded))
        "the declared name must match the directory it was loaded from")
    (is (= ["STU3" "R4" "R4B" "R5"] (:versions loaded))
        "the list carries version order, because these strings do not sort correctly")))

(deftest missing-corpus-names-what-it-looked-for
  (let [info (try (corpus/load "no-such-corpus") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= {:corpus "no-such-corpus" :resource "no-such-corpus/corpus.edn"} info))))

(deftest unreadable-edn-is-reported-as-such
  (is (not (succeed? #(corpus/load "broken-edn")))))

(deftest a-missing-required-key-names-it
  (let [info (try (corpus/load "missing-keys") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= [:fetch] (:missing-keys info)))
    (is (= [:corpus :sources] (:found-keys info)))))

(deftest a-source-missing-a-key-names-the-source
  (let [info (try (corpus/load "bad-source") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= "spec" (:name (:source info))))
    (is (= [:base-url] (:missing-keys info)))))

(deftest a-corpus-cannot-execute-code
  ;; The fixture is valid apart from #=(+ 1 1), which asks the reader to
  ;; evaluate. Were it evaluated, :delay-ms would be 2 and the load would
  ;; succeed -- so a failure here is the proof that it was not.
  (is (not (succeed? #(corpus/load "eval-attempt")))))

(deftest a-corpus-may-not-answer-to-two-names
  (let [info (try (corpus/load "mislabelled") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info)
        "the load must fail")
    (is (= "wrong-name" (:declared info)))))

(deftest seeds-one-row-per-source
  (with-temp-db
    (fn [conn]
      (let [rows (corpus/upsert-sources! conn two-sources)]
        (is (= ["pages" "repo"] (map :name rows)) "one row per source, in definition order")
        (is (every? :id rows)
            "every row comes back with its id")
        (is (= {:clone :shallow} (edn/read-string (:config (first rows))))
            "config is stored as EDN text and reads back unchanged")
        (is (nil? (:config (second rows)))
            "a source without config stores NULL")))))

(deftest re-running-keeps-the-ids
  (with-temp-db
    (fn [conn]
      (let [fst-ids (map :id (corpus/upsert-sources! conn two-sources))
            snd-ids (map :id (corpus/upsert-sources! conn two-sources))]
        (is (= fst-ids snd-ids)
            "an existing row is updated, not replaced")
        (is (= 2 (count (select-sources! conn)))
            "and no duplicate rows appear")))))

(deftest a-changed-base-url-is-written-through
  (with-temp-db
    (fn [conn]
      (corpus/upsert-sources! conn two-sources)
      (let [two-sources' (assoc-in two-sources [:sources 0 :base-url] "http://127.0.0.1/changed/")
            [fst-row _]  (corpus/upsert-sources! conn two-sources')]
        (is (= "http://127.0.0.1/changed/" (:base_url fst-row)))
        (is (= "http://127.0.0.1/changed/" (:base_url (first (select-sources! conn))))
            "the database row itself changed, not just the returned value")))))
