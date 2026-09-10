;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns fhir.parsers.spec-test
  "Tested against small zips the test writes itself, one per archive layout:
  the Windows-built ones with backslash separators, the Mac-built ones with
  their own directories and __MACOSX/ shadows."
  (:require [clojure.java.io   :as io]
            [clojure.test      :refer [deftest is]]
            [fhir.parsers.spec :as spec])
  (:import [java.io       File]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(def ^:private url-prefix "https://127.0.0.1/fhir/x/")

;; Entry name -> content, per layout. Written in this order, which is the
;; order an archive lists them back. Each entry exists to exercise one
;; condition of zip-entry->page.
(def ^:private windows-entries
  [["index.html"                     "<p>top level, outside site</p>"]
   ["site\\patient.html"             "<p>patient</p>"]
   ["site\\patient.json.html"        "<p>patient, rendered as JSON</p>"]
   ["site\\ehrsrle\\auditevent.html" "<p>nested page</p>"]
   ["site\\fhir.css"                 "body {}"]])

(def ^:private r4b-entries
  [["R4B-zip/patient.html"           "<p>patient</p>"]
   ["R4B-zip/patient.json.html"      "<p>patient, rendered as JSON</p>"]
   ["__MACOSX/R4B-zip/._patient.html" "mac metadata"]])

(def ^:private r5-entries
  [["fhir-spec/index.html"                    "<p>above the site directory</p>"]
   ["fhir-spec/site/patient.html"             "<p>patient</p>"]
   ["__MACOSX/fhir-spec/site/._patient.html"  "mac metadata"]])

(defn- write-zip!
  "A zip file holding the given entries.

  file     where to write it
  entries  [[entry name, content string] ...]

  Returns file."
  [^File file entries]
  (with-open [out (ZipOutputStream. (io/output-stream file))]
    (doseq [[^String name ^String content] entries]
      (.putNextEntry out (ZipEntry. name))
      (.write out (.getBytes content "UTF-8"))
      (.closeEntry out)))
  file)

(defn- with-archive
  "A freshly written zip, opened, then closed and deleted afterwards.

  entries  what to write into it, as write-zip! takes them
  f        the test body, given the open ZipFile

  Returns what f returns."
  [entries f]
  (let [file     (File/createTempFile "fhir-spec-test-" ".zip")
        silently true]
       (try
         (write-zip! file entries)
         (with-open [archive (ZipFile. file)]
           (f archive))
         (finally
           (io/delete-file file silently)))))

(deftest a-windows-built-archive-yields-the-pages-under-site
  (with-archive windows-entries
    (fn [archive]
        (let [pages (spec/pages archive "R4" url-prefix)]
             (is (= ["patient.html" "ehrsrle/auditevent.html"] (map :path pages))
                 "index.html is outside site, the JSON view is a rendering, the CSS is not a page")
             (is (= [(str url-prefix "patient.html") (str url-prefix "ehrsrle/auditevent.html")] (map :url pages))
                 "the URL is the prefix plus the path with slashes")
             (is (= (map :path pages) (map :path (spec/pages archive "STU3" url-prefix)))
                 "STU3 archives have the same layout")))))

(deftest an-r4b-archive-yields-the-pages-under-its-own-directory
  (with-archive r4b-entries
    (fn [archive]
        (is (= ["patient.html"] (map :path (spec/pages archive "R4B" url-prefix)))
            "the JSON view and the Mac shadow entry are not pages"))))

(deftest an-r5-archive-yields-the-pages-under-fhir-spec-site
  (with-archive r5-entries
    (fn [archive]
        (is (= ["patient.html"] (map :path (spec/pages archive "R5" url-prefix)))
            "the index above the site directory and the Mac shadow entry are not pages"))))

(deftest a-version-without-a-known-layout-is-refused
  (with-archive windows-entries
    (fn [archive]
        (let [message (try (spec/pages archive "R6" url-prefix)
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-message e)))]
             (is (= "Unknown FHIR version R6; known: STU3 R4 R4B R5" message)
                 "the error names the version and the ones it knows")))))

(deftest page-html-reads-one-page
  (with-archive windows-entries
    (fn [archive]
        (let [[patient] (spec/pages archive "R4" url-prefix)]
             (is (= "<p>patient</p>" (spec/page-html archive patient)) "the content comes back as written")))))
