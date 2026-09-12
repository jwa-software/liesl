;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.parse
  "Turns fetched archives into document rows, through the parser each source
  names in its config. This is the engine's only way to reach a corpus's
  parser: a symbol in corpus.edn, resolved by name."
  (:require [clojure.edn    :as edn]
            [liesl.corpus   :as corpus]
            [liesl.db       :as db]
            [liesl.document :as document]
            [liesl.fetch    :as fetch]
            [next.jdbc      :as jdbc])
  (:import [java.io File]))

;; ---- Pure helpers: no I/O ----

(defn- parser-symbol
  "The parser a source's config names.

  source  a source row; its config is EDN text

  Returns the symbol under :parser, e.g. fhir.parsers.spec/documents, or nil."
  [source]
  (:parser (some-> (:config source) edn/read-string)))

;; ---- Code loading ----

(defn- parser
  "The function a source's config names, with its namespace loaded.

  source  a source row whose config names a :parser

  Returns the function. A namespace that cannot be loaded is the loader's own
  error; a symbol naming nothing in a loaded namespace is an ex-info naming
  the symbol."
  [source]
  (let [sym (parser-symbol source)]
       (or (requiring-resolve sym)
           (throw (ex-info (format "%s config names a parser that does not exist: %s" (:name source) sym)
                           {:parser sym})))))

;; ---- Public, each built on the one above: a version list, a corpus, the
;; ---- command ----

(defn parse-versions!
  "Write the documents of a source's archive for each version in turn, each
  version in one transaction.

  conn      an open connection
  source    a source row, as upsert-sources! returns it; its config names the :parser
  versions  the version strings, parsed in this order
  dir       the directory all archives live under

  Returns
  [{:version <version> :documents <how many rows were written>}
   ...one map per version, in the order given...]

  A version whose archive is not under dir is an ex-info naming the file:
  fetching comes first."
  [conn {:keys [source versions dir]}]
  (let [parse (parser source)]
       (mapv (fn [version]
                 (let [{:keys [^File file url-prefix]} (fetch/archive-location {:source source :version version :dir dir})]
                      (when-not (.exists file)
                        (throw (ex-info (format "%s %s has not been fetched: no %s" (:name source) version file)
                                        {:file (str file)})))
                      (let [documents (parse file version url-prefix)]
                           (jdbc/with-transaction [tx conn]
                             (doseq [parsed documents]
                               (document/upsert! tx (:id source) (assoc parsed :kind (:kind source) :version version))))
                           {:version version :documents (count documents)})))
             versions)))

(defn parse-corpus!
  "Write a corpus's sources to the database and parse every source whose
  config names a :parser, version by version. The rest are skipped.

  conn        an open connection
  definition  what corpus/load returned
  versions    the version strings to parse; absent means the definition's :versions
  dir         the directory all archives live under

  Returns
  {<source name> [{:version <version> :documents <count>}
                  ...one map per version, in the order parsed...]
   ...one entry per source whose config names a :parser...}"
  [conn {:keys [definition versions dir]}]
  (let [versions (or versions (:versions definition))
        sources  (corpus/upsert-sources! conn definition)]
       (into {}
             (comp (filter parser-symbol)
                   (map (fn [source] [(:name source) (parse-versions! conn {:source source :versions versions :dir dir})])))
             sources)))

(defn ^:exec-fn parse
  "Parse a corpus's fetched archives into document rows. `clj -X:parse :corpus
  fhir`, optionally `:versions '[\"R4\"]'`. Takes the exec map because that is
  what -X passes.

  corpus    the corpus name, as a symbol or string
  versions  the version strings; absent means every version the corpus declares

  Prints one line per version parsed. Returns nil, because -X discards it."
  [{:keys [corpus versions]}]
  (with-open [conn (db/get-connection)]
    (doseq [[source-name results] (parse-corpus! conn {:definition (corpus/load (name corpus))
                                                       :versions   versions
                                                       :dir        (fetch/archives-dir)})
            {:keys [version documents]} results]
      (println source-name version documents "documents"))))
