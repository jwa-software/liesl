;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.corpus
  "Reads a corpus definition. The engine's only knowledge of a domain."
  ;; clojure.core is referred into every namespace automatically, so its `load`
  ;; would collide with ours. :exclude leaves that one name out.
  (:refer-clojure :exclude [load])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private required-top-level-keys #{:corpus :fetch :sources})
(def ^:private required-fetch-keys     #{:delay-ms})
(def ^:private required-source-keys    #{:name   :kind  :base-url})

(defn- check-keys
  "Throw unless every required key is present."
  [m required-keys what where]
  (let [missing-keys (remove (partial contains? m) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info (format "%s is missing required keys" what)
                      (assoc where
                             :missing-keys (vec (sort missing-keys))
                             :found-keys   (vec (sort (keys m)))))))))
(defn load
  "Read corpora/<corpus-name>/corpus.edn from the classpath."
  [corpus-name]
  (let [path     (str corpus-name "/corpus.edn")
        resource (io/resource path)
        where    {:corpus corpus-name :resource path}]
    (when-not resource
      (throw (ex-info "No such corpus on the classpath" where)))
    (let [parsed (try (edn/read-string (slurp resource))
                      (catch Exception e (throw (ex-info "Corpus definition is not a readable EDN" where e))))]
      (when-not (map? parsed) (throw (ex-info "Corpus definition is not a map" (assoc where :type (type parsed)))))
      (check-keys parsed          required-top-level-keys "Corpus definition" where)
      (check-keys (:fetch parsed) required-fetch-keys     "Corpus :fetch"     where)
      ;; The directory name is how a corpus is addressed, so a :corpus key that
      ;; disagrees with it would make the same corpus answer to two names.
      (when-not (= corpus-name (:corpus parsed))
        (throw (ex-info "Corpus name does not match its directory"
                        (assoc where :declared (:corpus parsed)))))
      (doseq [source (:sources parsed)]
        (check-keys source
                    required-source-keys
                    "Corpus source"
                    (assoc where :source source)))
      parsed)))
