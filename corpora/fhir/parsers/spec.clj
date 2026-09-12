;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns fhir.parsers.spec
  "Reads the pages of one published FHIR version out of its fhir-spec.zip, and
  turns a page into the text a document row holds.

  Each version's archive keeps its pages under a directory of its own choosing,
  with backslash separators where it was built on Windows. Most of its HTML is
  the same page rendered as JSON, XML or Turtle; only the prose pages are worth
  keeping. Every prose page has the same skeleton around one content column,
  and the same boilerplate inside it, in all four versions."
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipEntry ZipFile]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element TextNode]
           [org.jsoup.select NodeVisitor]))

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

;; The one element holding a page's content. A file without it is not a page:
;; a "Not generated in this build" stub, or a fragment the tooling left behind.
(def ^:private ^String content-column "div.col-12")

;; What the content column carries that is not the page's own text: the
;; "downloaded copy" notice, the page family's tab strip (Content, Examples,
;; Detailed Descriptions, ...), the work group and maturity tables, the ANSI
;; box, the self-link icon after each heading and the UML diagram, and the
;; structure views (element table, UML, XML, JSON, Turtle, and all of them once
;; more). The element table is on each resource's own -definitions.html, so
;; dropping the views loses nothing.
(def ^:private ^String boilerplate
  "p#publish-box, ul.nav-tabs, table.colsn, table.colsi, table.none, svg, div#tabs")

;; Every title ends in the version's build number: "Patient - FHIR v4.0.1".
(def ^:private title-suffix #" - FHIR v[\d.]+$")

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

(defn- head-title
  "The head title, without the version's build number.

  doc  the parsed page

  Returns e.g. \"Patient\" for \"Patient - FHIR v4.0.1\", or nil when the head has
  no title. It is the first title that says anything: one page carries an
  empty title element before its real one."
  [^Document doc]
  (let [titles (map (fn [^Element title] (.text title))
                    (.select doc "head > title"))]
       (when-let [title (first (remove str/blank? titles))]
         (str/replace title title-suffix ""))))

(defn- page-title
  "The page's title: its first heading without the section number. The head
  title names the whole family of a resource's pages alike, while the heading
  says which one this is.

  doc     the parsed page
  column  its content column, boilerplate already removed

  Returns e.g. \"Resource Patient - Content\" from the heading
  \"8.1 Resource Patient - Content\"; the head title when there is no heading."
  [^Document doc ^Element column]
  (if-let [heading (.selectFirst column "h1, h2, h3, h4, h5, h6")]
    ;; A copy, so the body keeps the number on its first line like every
    ;; other heading.
    (let [heading (.clone heading)]
         (.remove (.select heading "span.sectioncount"))
         (.text heading))
    (head-title doc)))

(defn- block-text
  "An element's text, one line per block-level element.

  element  the content column, boilerplate already removed

  Returns the lines joined by newlines, each trimmed and its whitespace
  collapsed, blank lines dropped: a heading, a paragraph, a list item or a
  table cell per line."
  [^Element element]
  (let [out (StringBuilder.)]
       ;; Text nodes are appended as they come; leaving a block-level element
       ;; ends the line. So a cell holding a paragraph gives one line, not two.
       (.traverse element
                  (reify NodeVisitor
                    (head [_ node _]
                      (when (instance? TextNode node)
                        (.append out (.text ^TextNode node))))
                    (tail [_ node _]
                      (when (and (instance? Element node) (.isBlock ^Element node))
                        (.append out "\n")))))
       (->> (str/split-lines (str out))
            (map #(str/replace (str/trim %) #"\s+" " "))
            (remove str/blank?)
            (str/join "\n"))))

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

(defn page->document
  "What a page contributes to a document row: its URL, title and body.

  page  one map from pages
  html  the page's content, from page-html

  Returns
  {:url   <the page's url>
   :title <the first heading without its section number, e.g. \"Resource Patient - Content\";
           the head title without the version's build number when there is no heading>
   :body  <the content column's text, one line per block-level element>}
  nil  a file without a content column: a questionnaire stub, or a fragment
       the tooling left under html/"
  [page ^String html]
  (let [doc    (Jsoup/parse html)
        column (.selectFirst doc content-column)]
       (when column
         (.remove (.select column boilerplate))
         {:url   (:url page)
          :title (page-title doc column)
          :body  (block-text column)})))
