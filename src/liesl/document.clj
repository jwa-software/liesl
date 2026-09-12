;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.document
  "Writes document rows: the durable copy of what a parser produced, which
  the index is derived from."
  (:require [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.charset StandardCharsets]
           [java.security     MessageDigest]))

;; ---- Pure helpers: no I/O ----

(defn- sha-256
  "Digest of a string.

  s  the text, to be hashed as UTF-8

  Returns the 32 SHA-256 bytes."
  ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes s StandardCharsets/UTF_8)))

;; ---- Public ----

(defn upsert!
  "One document row, upserted on url so ids survive a re-run.

  conn       an open connection
  source-id  the id of the source row the document belongs to
  document   {:url     <string, the cross-machine identity>
              :kind    <string, e.g. \"spec\">
              :title   <string, or nil>
              :body    <string, what gets indexed>
              :version <string, e.g. \"R4\", or nil for an unversioned source>}

  Returns
  {:id           <integer, the row id>
   :source_id    <integer>
   :kind         <string>
   :url          <string>
   :title        <string, or nil>
   :version      <string, or nil>
   :content_hash <32 bytes: SHA-256 over the title, a newline and the body>
   :indexed_at   <string, or nil>}

  A row whose title and body both came back unchanged keeps its indexed_at;
  any other update clears it, which puts the row back on the indexer's queue."
  [conn source-id {:keys [url kind title body version]}]
  (jdbc/execute-one!
    conn
    [(str "INSERT INTO document (source_id, kind, url, title, body, version, content_hash) "
          "  VALUES (?, ?, ?, ?, ?, ?, ?) "
          "ON CONFLICT (url) DO UPDATE SET "
          "  source_id    = excluded.source_id, "
          "  kind         = excluded.kind, "
          "  title        = excluded.title, "
          "  body         = excluded.body, "
          "  version      = excluded.version, "
          "  content_hash = excluded.content_hash, "
          "  indexed_at   = CASE WHEN document.content_hash = excluded.content_hash "
          "                      THEN document.indexed_at ELSE NULL END "
          "RETURNING id, source_id, kind, url, title, version, content_hash, indexed_at")
     source-id kind url title body version (sha-256 (str title "\n" body))]
    {:builder-fn rs/as-unqualified-lower-maps}))
