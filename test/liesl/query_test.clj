;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.query-test
  "Against an index of three small pages, built the way `clj -X:index` builds
  it: rows through `liesl.document/upsert!`, then `index-unindexed!`."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is]]
            [liesl.corpus    :as corpus]
            [liesl.db        :as db]
            [liesl.document  :as document]
            [liesl.index     :as index]
            [liesl.query     :as query])
  (:import [java.nio.file           Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private one-source
  {:corpus  "test"
   :sources [{:name "pages" :kind "spec" :base-url "http://127.0.0.1/"}]})

;; Two R4 pages and one R5 page. The word birthTime is in every body and in
;; one title, so the tests can tell a title hit from a body hit.
(def ^:private patient
  {:url     "http://127.0.0.1/R4/patient.html"
   :kind    "spec"
   :title   "Resource Patient - Content"
   :body    "Demographics of a patient. The birthTime extension records the time of birth."
   :version "R4"})

(def ^:private birth-time
  {:url     "http://127.0.0.1/R4/extension-patient-birthtime.html"
   :kind    "spec"
   :title   "Extension: birthTime"
   :body    "The time of day that the patient was born."
   :version "R4"})

(def ^:private patient-r5
  {:url     "http://127.0.0.1/R5/patient.html"
   :kind    "spec"
   :title   "Resource Patient - Content"
   :body    "Demographics of a patient, R5 edition, with birthTime too."
   :version "R5"})

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-query-test-" ".db")
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
  (let [dir      (.toFile (Files/createTempDirectory "liesl-query-test-" (make-array FileAttribute 0)))
        silently true]
       (try
         (f dir)
         (finally
           ;; `file-seq` lists a directory before its contents; reversed, children go first.
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file silently))))))

(defn- with-index
  "The three pages indexed into a fresh directory, opened for reading, then
  closed and removed afterwards.

  f  the test body, given what `query/open` returned

  Returns what f returns."
  [f]
  (with-temp-db
    (fn [conn]
        (with-temp-dir
          (fn [dir]
              (let [source-id (:id (first (corpus/upsert-sources! conn one-source)))]
                   (doseq [page [patient birth-time patient-r5]]
                     (document/upsert! conn source-id page))
                   (let [writer (index/open dir)]
                        (try
                          (index/index-unindexed! conn writer)
                          (finally
                            (index/close writer))))
                   (let [opened (query/open dir)]
                        (try
                          (f opened)
                          (finally
                            (query/close opened))))))))))

(deftest a-word-finds-every-page-that-carries-it
  (with-index
    (fn [opened]
        (is (= #{(:url patient) (:url birth-time) (:url patient-r5)}
               (set (map :url (query/hits opened {:q "birthTime"}))))
            "all three carry the word, in a title or a body; case does not matter"))))

(deftest a-word-in-the-title-ranks-above-the-same-word-in-the-body
  (with-index
    (fn [opened]
        (is (= (:url birth-time) (:url (first (query/hits opened {:q "birthTime"}))))
            "the page titled with the word comes first"))))

(deftest a-version-filter-keeps-only-that-version
  (with-index
    (fn [opened]
        (let [found (query/hits opened {:q "birthTime" :version "R4"})]
             (is (= 2 (count found))                      "the R5 page is out")
             (is (every? #(= "R4" (:version %)) found)    "and both left are R4")))))

(deftest a-kind-filter-keeps-only-that-kind
  (with-index
    (fn [opened]
        (is (empty? (query/hits opened {:q "birthTime" :kind "chat"})) "nothing indexed is chat"))))

(deftest the-snippet-shows-the-passage-that-matched
  (with-index
    (fn [opened]
        (let [snippet (:snippet (first (filter #(= (:url patient) (:url %)) (query/hits opened {:q "birthTime"}))))]
             (is (str/includes? snippet "birthTime")          "the word is there, in the page's own spelling")
             (is (not (str/includes? snippet "<b>"))           "and not wrapped in a mark")
             (is (not (str/includes? snippet "Demographics"))  "and the sentence before it is not part of the passage")))))

(deftest limit-caps-the-hits
  (with-index
    (fn [opened]
        (is (= 1 (count (query/hits opened {:q "birthTime" :limit 1}))) "one asked for, one given"))))

(deftest no-match-is-an-empty-vector
  (with-index
    (fn [opened]
        (is (= [] (query/hits opened {:q "haemoglobin"})) "a word no page has"))))

(deftest a-page-comes-back-whole-by-its-url
  (with-index
    (fn [opened]
        (is (= patient (query/page opened (:url patient)))  "every stored field, as it was written")
        (is (nil? (query/page opened "http://127.0.0.1/R4/nowhere.html")) "no page, nil"))))

(deftest a-question-lucene-cannot-parse-is-refused
  (with-index
    (fn [opened]
        (let [message (try (query/hits opened {:q "\"unbalanced"}) nil
                           (catch clojure.lang.ExceptionInfo e (ex-message e)))]
             (is (= "Cannot parse query \"\\\"unbalanced\"" message) "the error quotes the question")))))
