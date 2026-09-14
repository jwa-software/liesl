;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.link
  "Writes and reads the edges between documents: for now, which pages are one
  page in different versions. The engine knows no corpus; it knows from
  `corpus.edn` that a source published by version keeps each version's pages
  under a URL of its own, and that what follows that URL names the page."
  (:require [clojure.edn          :as edn]
            [clojure.string       :as str]
            [liesl.corpus         :as corpus]
            [liesl.db             :as db]
            [liesl.fetch          :as fetch]
            [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]))

;; The one kind of edge written so far: the same page in another version.
(def ^:private version-of "version-of")

;; ---- Pure helpers: no I/O ----

(defn- versioned?
  "Whether a source is published by version.

  source  a source row; its config is EDN text

  Returns true when the config names a :version-path."
  [source]
  (boolean (:version-path (some-> (:config source) edn/read-string))))

(defn- path
  "The part of a page's url after its version's prefix: what names the page
  across versions.

  url       the page's url
  version   the page's version
  prefixes  {<version> <url prefix> ...}

  Returns e.g. \"patient.html\" for \"https://hl7.org/fhir/R4/patient.html\", or nil
  when the url does not start with its version's prefix."
  [url version prefixes]
  (let [prefix (get prefixes version)]
       (when (and prefix (str/starts-with? url prefix))
         (subs url (count prefix)))))

(defn- pairs
  "Every two of the ids, the lower first.

  ids  integers, in any order

  Returns [[a b] ...] with a < b in each."
  [ids]
  (let [ids (sort ids)]
       (for [a ids
             b ids
             :when (< a b)]
         [a b])))

(defn version-of-pairs
  "Pair up the documents that are one page in different versions: those
  whose urls share the path after their version's prefix.

  documents  [{:id :version :url} ...], every document of one source
  prefixes   {<version> <url prefix> ...}, as `fetch/version-url` gives them

  Returns
  [[<from-id> <to-id>] ...one per two documents of one page, the lower id first,
   in no particular order...]
  A page present in one version only yields nothing, and so does a document
  whose url is not under its version's prefix."
  [documents prefixes]
  (let [groups (dissoc (group-by (fn [document] (path (:url document) (:version document) prefixes))
                                 documents)
                       nil)]
       (into []
             (comp (filter #(> (count %) 1))
                   (mapcat #(pairs (map :id %))))
             (vals groups))))

;; ---- Database ----

(defn- documents!
  "Every document of a source, with what the pairing needs.

  conn       an open connection
  source-id  the source row's id

  Returns a vector of {:id :version :url}."
  [conn source-id]
  (jdbc/execute! conn
                 ["SELECT id, version, url FROM document WHERE source_id = ?" source-id]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- insert-edges!
  "Write edges, skipping any already there, all in one transaction.

  conn   an open connection
  kind   the edges' kind, e.g. \"version-of\"
  pairs  [[from-id to-id] ...]

  Returns how many were new."
  [conn kind pairs]
  (jdbc/with-transaction [tx conn]
    (reduce + 0
            (map (fn [[from to]]
                     (-> (jdbc/execute-one! tx
                                            ["INSERT OR IGNORE INTO link (from_id, to_id, kind) VALUES (?, ?, ?)" from to kind])
                         ::jdbc/update-count))
                 pairs))))

;; ---- Public, each built on the one above: a source, a corpus, the command;
;; ---- then reading the edges back ----

(defn link-versions!
  "Pair every page of a source with the same page in its other versions, and
  write the pairs as `version-of` edges.

  conn      an open connection
  source    a source row, as `upsert-sources!` returns it; its config names a :version-path
  versions  the version strings the source has

  Returns how many edges were new; a second run writes nothing."
  [conn {:keys [source versions]}]
  (let [prefixes (into {}
                       (map (fn [version] [version (fetch/version-url source version)]))
                       versions)]
       (insert-edges! conn version-of (version-of-pairs (documents! conn (:id source)) prefixes))))

(defn link-corpus!
  "Write the `version-of` edges of every source of a corpus that is published
  by version. The rest are skipped.

  conn        an open connection
  definition  what `corpus/load` returned

  Returns
  {<source name> <how many edges were new>
   ...one entry per source whose config names a :version-path...}"
  [conn {:keys [definition]}]
  (let [versions (:versions definition)
        sources  (corpus/upsert-sources! conn definition)]
       (into {}
             (comp (filter versioned?)
                   (map (fn [source] [(:name source) (link-versions! conn {:source source :versions versions})])))
             sources)))

(defn ^:exec-fn link
  "Write the edges of a corpus. `clj -X:link :corpus fhir`, after `clj -X:parse`.
  Takes the exec map because that is what `-X` passes.

  corpus  the corpus name, as a symbol or string

  Prints one line per source. Returns nil, because `-X` discards it."
  [{:keys [corpus]}]
  (with-open [conn (db/get-connection)]
    (doseq [[source-name new-edges] (link-corpus! conn {:definition (corpus/load (name corpus))})]
      (println source-name new-edges "new edges"))))

(defn related
  "The pages that are one page in other versions: what `version-of` edges
  reach from a page, in either direction.

  conn  an open connection
  url   the page's url

  Returns
  [{:url <string> :version <string, or nil> :title <string, or nil>}
   ...one per counterpart, in no particular order...]
  []   the page exists and has no counterpart
  nil  no page has that url"
  [conn url]
  (when-let [{:keys [id]} (jdbc/execute-one! conn
                                             ["SELECT id FROM document WHERE url = ?" url]
                                             {:builder-fn rs/as-unqualified-lower-maps})]
    (jdbc/execute! conn
                   [(str "SELECT d.url, d.version, d.title "
                         "FROM link l "
                         "JOIN document d ON d.id = CASE WHEN l.from_id = ? THEN l.to_id ELSE l.from_id END "
                         "WHERE l.kind = ? AND (l.from_id = ? OR l.to_id = ?)")
                    id version-of id id]
                   {:builder-fn rs/as-unqualified-lower-maps})))
