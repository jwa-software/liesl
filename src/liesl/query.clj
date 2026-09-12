;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.query
  "Answers a question from the index: a question in, ranked hits with a
  snippet out. Reads only; `liesl.index` is what writes."
  (:require [clojure.java.io :as io]
            [liesl.index     :as index])
  (:import [org.apache.lucene.index                DirectoryReader Term]
           [org.apache.lucene.queryparser.classic  MultiFieldQueryParser ParseException]
           [org.apache.lucene.search               BooleanClause$Occur BooleanQuery$Builder IndexSearcher Query ScoreDoc TermQuery]
           [org.apache.lucene.search.uhighlight    UnifiedHighlighter]
           [org.apache.lucene.store                FSDirectory]))

;; Where a bare word in a question is looked for. A word in the title is
;; worth more than the same word in the body, and that is what two fields buy.
(def ^:private searched-fields (into-array String ["title" "body"]))

(def ^:private default-limit 10)

;; ---- Pure helpers: no I/O ----

(defn- parse
  "Turn what a person typed, e.g. `birthTime` or `\"patient birth\"`, into a
  query Lucene can run. Lucene does not take strings; it takes `Query` objects.

  q  the question, in Lucene's own syntax: bare words, quoted phrases,
     `field:value`

  Returns the `Query`. A question Lucene cannot parse is an `ex-info` naming
  it, with the parser's own error as the cause."
  ^Query [^String q]
  (try
    (.parse (MultiFieldQueryParser. searched-fields (index/analyzer))
            q)
    (catch ParseException e
      (throw (ex-info (format "Cannot parse query %s" (pr-str q))
                      {:q q}
                      e)))))

(defn- with-filters
  "The query narrowed to one version and one kind, where given.

  query    the parsed question
  version  e.g. \"R4\", or nil for every version
  kind     e.g. \"spec\", or nil for every kind

  Returns a `Query`. The filters decide who is in; they do not move the score."
  ^Query [^Query query version kind]
  (let [builder (doto
                  (BooleanQuery$Builder.)
                  (.add query BooleanClause$Occur/MUST))]
       (when version (.add builder
                           (TermQuery. (Term. "version" ^String version))
                           BooleanClause$Occur/FILTER))
       (when kind    (.add builder
                           (TermQuery. (Term. "kind"    ^String kind))
                           BooleanClause$Occur/FILTER))
       (.build builder)))

;; ---- Public, each built on the one above: open and close, the hits, the
;; ---- command ----

(defn open
  "Open the index for reading. What it sees is the index as of now; rows
  indexed afterwards need a new `open`.

  dir  the index directory

  Returns {:directory <the FSDirectory> :reader <the DirectoryReader> :searcher <the IndexSearcher>},
  for `close`. A directory with no index in it is Lucene's own error."
  [dir]
  (let [directory (FSDirectory/open (.toPath (io/file dir)))
        reader    (DirectoryReader/open directory)]
       {:directory directory
        :reader    reader
        :searcher  (IndexSearcher. reader)}))

(defn close
  "Close what `open` returned.

  opened  what `open` returned

  Returns nil."
  [{:keys [^DirectoryReader reader ^FSDirectory directory]}]
  (.close reader)
  (.close directory))

(defn hits
  "The pages that answer a question, best first.

  opened   what `open` returned
  q        the question, in Lucene's syntax; a bare word is looked for in the
           title and the body
  version  only this version, e.g. \"R4\"; absent means every version
  kind     only this kind, e.g. \"spec\"; absent means every kind
  limit    at most this many hits; absent means 10

  Returns
  [{:url     <string>
    :title   <string, or nil>
    :version <string, or nil>
    :kind    <string>
    :score   <float, Lucene's own, higher is better>
    :snippet <string: the body passage that matched, the words in <b>; the
              first passage when none did>}
   ...best first...]"
  [{:keys [^IndexSearcher searcher ^DirectoryReader reader]} {:keys [q version kind limit]}]
  (let [query    (with-filters (parse q) version kind)
        found    (.search searcher query (int (or limit default-limit)))
        fields   (.storedFields reader)
        snippets (.highlight (.build (UnifiedHighlighter/builder searcher (index/analyzer)))
                             "body"
                             query
                             found)]
       (mapv (fn [^ScoreDoc hit ^String snippet]
                 (let [doc (.document fields (.-doc hit))]
                      {:url     (.get doc "url")
                       :title   (.get doc "title")
                       :version (.get doc "version")
                       :kind    (.get doc "kind")
                       :score   (.-score hit)
                       :snippet (or snippet "")}))
             (.-scoreDocs found)
             snippets)))

(defn ^:exec-fn search
  "Ask the index a question from the shell. `clj -X:search :q birthTime
  :version R4`; several words are quoted, `:q '\"patient birth\"'`. Takes the
  exec map because that is what `-X` passes.

  q        the question; a bare word arrives as a symbol and is read as its name
  version  only this version, or absent
  kind     only this kind, or absent
  limit    at most this many hits; absent means 10

  Prints one line per hit -- score, version, url, title -- and the snippet
  under it. Returns nil, because `-X` discards it."
  [{:keys [q version kind limit]}]
  (when-not q
    (throw (ex-info "A question is needed: clj -X:search :q <word>" {})))
  (let [opened (open (index/index-dir))]
       (try
         (doseq [hit (hits opened {:q       (str q)
                                   :version (some-> version str)
                                   :kind    (some-> kind str)
                                   :limit   limit})]
           (println (format "%.3f %s %s | %s"
                            (:score hit)
                            (:version hit)
                            (:url hit)
                            (:title hit)))
           (println "     "
                    (:snippet hit)))
         (finally
           (close opened)))))
