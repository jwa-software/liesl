;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns fhir.parsers.spec
  "Reads the pages of one published FHIR version out of its fhir-spec.zip.

  Each version's archive keeps its pages under a directory of its own choosing,
  with backslash separators where it was built on Windows. Most of its HTML is
  the same page rendered as JSON, XML or Turtle; only the prose pages are worth
  keeping."
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipEntry ZipFile]))

;; The directory each version keeps its pages under, as a prefix of the entry
;; name. STU3 and R4 were built on Windows, R4B and R5 on a Mac, each with a
;; different one; a Mac archive also carries __MACOSX/ shadow entries, which
;; never start with the prefix and so need no special case.
(def ^:private page-dirs
  {"STU3" "site/"
   "R4"   "site/"
   "R4B"  "R4B-zip/"
   "R5"   "fhir-spec/site/"})

;; The same page rendered in another syntax, e.g. patient.json.html.
(def ^:private rendered-view #"\.(json|xml|ttl|shex|sch|canonical)\.html$")

;; ---- Pure helpers: no I/O ----

(defn- windows->unix
  "A path with backslashes turned into slashes.

  path  e.g. \"site\\patient.html\", one backslash, as the archive names its entries

  Returns \"site/patient.html\"."
  [path]
  (str/replace path "\\" "/"))

(defn- zip-entry->page
  "The page an archive entry is, or nil for an entry that is not a page.

  entry       a ZipEntry
  dir         the version's entry from page-dirs, e.g. \"site/\"
  url-prefix  the version's base URL, e.g. \"https://hl7.org/fhir/R4/\"

  Returns
  {:path  <path under dir, with slashes, e.g. \"patient.html\">
   :url   <url-prefix + path>
   :entry <the ZipEntry, for page-html>}
  nil  a directory
  nil  a file outside dir, such as a stray index.html or a __MACOSX/ shadow
  nil  a file that is not HTML, such as fhir.css
  nil  a rendered view, such as patient.json.html"
  [^ZipEntry entry ^String dir ^String url-prefix]
  (let [name (windows->unix (.getName entry))]
    (when (and (not (.isDirectory entry))
               (str/starts-with? name dir)
               (str/ends-with?   name ".html")
               (not (re-find rendered-view name)))
      (let [path (subs name (count dir))]
        {:path path :url (str url-prefix path) :entry entry}))))

;; ---- Public ----

(defn pages
  "Every prose page in an archive, with the URL hl7.org serves it at.

  archive     an open ZipFile; the caller closes it, so use the pages before then
  version     which version the archive is, e.g. \"R4\"; must be in page-dirs
  url-prefix  the version's base URL, e.g. \"https://hl7.org/fhir/R4/\"

  Returns
  [<page, as zip-entry->page describes it>
   ...one per page, in archive order...]

  An unknown version is an ex-info naming it and the known ones."
  [^ZipFile archive ^String version ^String url-prefix]
  (let [dir (get page-dirs version)]
    (when-not dir
      (throw (ex-info (format "Unknown FHIR version %s; known: %s" version (str/join " " (keys page-dirs)))
                      {:version version})))
    (into []
          (keep #(zip-entry->page % dir url-prefix))
          (enumeration-seq (.entries archive)))))

(defn page-html
  "One page's HTML, read from the archive on demand.

  archive  the open ZipFile the page came from
  page     one map from pages

  Returns the page's content as a UTF-8 string."
  ^String [^ZipFile archive page]
  (let [^ZipEntry entry (:entry page)]
    (with-open [in (.getInputStream archive entry)]
      (slurp in :encoding "UTF-8"))))
