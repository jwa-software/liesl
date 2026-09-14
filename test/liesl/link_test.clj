;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.link-test
  "The pairing on its own, then the edges on a fresh migrated database with
  pages in three versions."
  (:require [clojure.java.io      :as io]
            [clojure.test         :refer [deftest is]]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [liesl.document       :as document]
            [liesl.link           :as link]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private prefixes
  {"STU3" "http://127.0.0.1/STU3/"
   "R4"   "http://127.0.0.1/R4/"
   "R5"   "http://127.0.0.1/R5/"})

;; One page in three versions, one page in a single version, and one whose
;; url is not under its version's prefix.
(def ^:private documents
  [{:id 1 :version "R4"   :url "http://127.0.0.1/R4/patient.html"}
   {:id 2 :version "R5"   :url "http://127.0.0.1/R5/patient.html"}
   {:id 3 :version "STU3" :url "http://127.0.0.1/STU3/patient.html"}
   {:id 4 :version "R4"   :url "http://127.0.0.1/R4/only-in-r4.html"}
   {:id 5 :version "R4"   :url "http://elsewhere/R4/patient.html"}])

;; One source published by version, one that is not.
(def ^:private definition
  {:corpus   "test"
   :versions ["STU3" "R4" "R5"]
   :sources  [{:name     "pages"
               :kind     "spec"
               :base-url "http://127.0.0.1/"
               :config   {:version-path "{version}/" :archive "pages.zip"}}
              {:name     "repo"
               :kind     "source"
               :base-url "http://127.0.0.1/repo.git"}]})

(defn- with-temp-db
  "A connection to a fresh migrated database, deleted afterwards.

  f  the test body, given the open connection

  Returns what f returns."
  [f]
  (let [file     (java.io.File/createTempFile "liesl-link-test-" ".db")
        silently true]
       (try
         (db/migrate {:db-file file})
         (with-open [conn (db/get-connection (db/db-spec file))]
           (f conn))
         (finally
           (doseq [suffix ["" "-wal" "-shm"]
                   :let   [target (io/file (str file suffix))]]
             (io/delete-file target silently))))))

(defn- insert-pages!
  "The definition's source rows, and a page in three versions plus one lone
  page under the versioned source.

  conn  an open connection

  Returns the versioned source row."
  [conn]
  (let [[pages] (corpus/upsert-sources! conn definition)]
       (doseq [[version path] [["STU3" "patient.html"] ["R4" "patient.html"] ["R5" "patient.html"] ["R4" "only-in-r4.html"]]]
         (document/upsert! conn (:id pages) {:url     (str (get prefixes version) path)
                                             :kind    "spec"
                                             :title   path
                                             :body    (str path " in " version)
                                             :version version}))
       pages))

(defn- select-edges!
  "Every edge, as [from-id to-id kind].

  conn  an open connection

  Returns a set."
  [conn]
  (into #{}
        (map (juxt :from_id :to_id :kind))
        (jdbc/execute! conn ["SELECT from_id, to_id, kind FROM link"] {:builder-fn rs/as-unqualified-lower-maps})))

(deftest pages-sharing-a-path-are-paired-lower-id-first
  (is (= #{[1 2] [1 3] [2 3]} (set (link/version-of-pairs documents prefixes)))
      "three versions of one page make three pairs; the lone page and the page under a foreign url make none"))

(deftest the-pair-order-does-not-depend-on-the-input-order
  (is (= [[2 9]] (link/version-of-pairs [{:id 9 :version "R4" :url "http://127.0.0.1/R4/a.html"}
                                         {:id 2 :version "R5" :url "http://127.0.0.1/R5/a.html"}]
                                        prefixes))
      "the lower id is from_id whichever came first"))

(deftest a-source-gets-one-edge-per-pair-of-versions-of-a-page
  (with-temp-db
    (fn [conn]
        (let [pages (insert-pages! conn)
              ids   (into {} (map (juxt :url :id)) (jdbc/execute! conn ["SELECT id, url FROM document"] {:builder-fn rs/as-unqualified-lower-maps}))
              id    #(get ids (str (get prefixes %1) %2))
              patient (sort [(id "STU3" "patient.html") (id "R4" "patient.html") (id "R5" "patient.html")])]
             (is (= 3 (link/link-versions! conn {:source pages :versions ["STU3" "R4" "R5"]})) "three new edges")
             (is (= (set (for [a patient b patient :when (< a b)] [a b "version-of"])) (select-edges! conn))
                 "one version-of edge per pair of the patient page's versions, and none for the lone page")))))

(deftest a-second-run-writes-nothing-new
  (with-temp-db
    (fn [conn]
        (let [pages (insert-pages! conn)]
             (link/link-versions! conn {:source pages :versions ["STU3" "R4" "R5"]})
             (is (= 0 (link/link-versions! conn {:source pages :versions ["STU3" "R4" "R5"]})) "every pair is already there")
             (is (= 3 (count (select-edges! conn)))                                            "and nothing was duplicated")))))

(deftest a-corpus-links-its-versioned-sources-and-skips-the-rest
  (with-temp-db
    (fn [conn]
        (insert-pages! conn)
        (is (= {"pages" 3} (link/link-corpus! conn {:definition definition}))
            "the repo source has no versions and is not in the result"))))
