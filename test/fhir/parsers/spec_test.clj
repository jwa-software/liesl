;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns fhir.parsers.spec-test
  "Tested against small zips the test writes itself, one per archive layout:
  the Windows-built ones with backslash separators, the Mac-built ones with
  their own directories and __MACOSX/ shadows. The parser is tested against a
  page cut down to its skeleton."
  (:require [clojure.java.io   :as io]
            [clojure.string    :as str]
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

;; The page map pages would build for patient.html; the entry is not read here.
(def ^:private patient
  {:path "patient.html" :url (str url-prefix "patient.html")})

;; A page cut down to what page->document reads: the head title with the
;; version's build number, the content column with each kind of boilerplate
;; around the prose, and chrome outside the column. Attributes are in single
;; quotes so the string needs no escapes.
(def ^:private patient-page
  "<html>
   <head><title>Patient - FHIR v4.0.1</title></head>
   <body>
   <div id='segment-navbar'><a href='index.html'>Home</a></div>
   <div id='segment-content'><div class='col-12'>
     <p id='publish-box'>This page is part of a downloaded copy of this specification.</p>
     <ul class='nav nav-tabs'><li>Content</li><li>Examples</li></ul>
     <table class='colsn'><tr><td>Patient Administration Work Group</td></tr></table>
     <h1><span class='sectioncount'>8.1</span> Resource Patient - Content
         <a class='self-link'><svg><title>link to here</title></svg></a></h1>
     <p>Demographics and other administrative
        information about an individual.</p>
     <div id='tabs'><div id='tabs-json'><pre>{ 'resourceType': 'Patient' }</pre></div></div>
     <h2><a name='scope'></a>8.1.1 Scope and Usage</h2>
     <ul><li>Curative activities</li><li>Psychiatric care</li></ul>
     <table class='dict'><tr><td>Definition</td><td><p>An identifier for this patient.</p></td></tr></table>
   </div></div>
   <div id='segment-footer'><p>HL7.org 2011+. FHIR Release 4</p></div>
   </body>
   </html>")

;; What is left of patient-page once the boilerplate and the chrome are gone.
(def ^:private patient-body
  (str/join "\n" ["8.1 Resource Patient - Content"
                  "Demographics and other administrative information about an individual."
                  "8.1.1 Scope and Usage"
                  "Curative activities"
                  "Psychiatric care"
                  "Definition"
                  "An identifier for this patient."]))

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

(deftest page->document-keeps-the-prose-and-drops-the-boilerplate
  (let [document (spec/page->document patient patient-page)]
       (is (= (str url-prefix "patient.html") (:url document)) "the URL is the page's")
       (is (= "Resource Patient - Content" (:title document)) "the first heading, without its section number")
       (is (= patient-body (:body document))
           "the notice, tab strip, work group table, icon, structure views and footer are gone; a cell holding a paragraph is one line; the first line keeps its number")))

(deftest without-a-heading-the-title-is-the-first-head-title-that-says-anything
  (let [html "<html><head><title></title><title>Ehrsrle - FHIR v4.0.1</title></head><body><div class='col-12'><p>x</p></div></body></html>"]
       (is (= "Ehrsrle" (:title (spec/page->document patient html)))
           "the build number is dropped; one page carries an empty title element before its real one")))

(deftest a-file-without-a-content-column-is-not-a-document
  (is (nil? (spec/page->document patient "<html><p>Not generated in this build</p></html>"))
      "the questionnaire stubs and the html/ fragments have no column"))
