-- Every table is STRICT, which makes SQLite enforce declared column types
-- instead of treating them as advisory. Without it a text value lands
-- silently in an integer column; with it the insert fails. STRICT permits
-- only INT, INTEGER, REAL, TEXT, BLOB and ANY, which is why timestamps are
-- TEXT and JSON is TEXT.
--
-- Timestamps are ISO-8601 in UTC ('2026-09-05T16:21:12Z'). That format sorts
-- correctly as text, so range scans and ORDER BY work without conversion,
-- and it stays readable when inspecting the file with the sqlite3 shell.
--
-- SQLite does not enforce foreign keys unless the connection asks it to:
-- PRAGMA foreign_keys defaults to 0, per connection, every time. The
-- REFERENCES clauses below are inert until whatever opens connections turns
-- it on. They are declared anyway because they document the shape and
-- because they start working the moment the pragma is set.

CREATE TABLE source (
  id       INTEGER PRIMARY KEY,
  corpus   TEXT NOT NULL,
  name     TEXT NOT NULL,
  kind     TEXT NOT NULL,
  base_url TEXT NOT NULL,
  -- JSON. Whatever the corpus package needs to drive its own fetching;
  -- the engine passes it through without interpreting it.
  config   TEXT,
  UNIQUE (corpus, name)
) STRICT;

--;;

-- Crawl bookkeeping, one row per URL, kept separate from the documents that
-- URL produced: one fetched page can yield hundreds of documents, and a URL
-- that yields nothing still has to be remembered so it is not re-fetched.
CREATE TABLE fetch_state (
  url           TEXT PRIMARY KEY,
  source_id     INTEGER NOT NULL REFERENCES source(id),
  -- Conditional-request tokens. Sending these back turns a re-fetch into a
  -- 304 that transfers no body, which is what makes fetching the whole
  -- archive repeatedly acceptable to the server being crawled.
  etag          TEXT,
  last_modified TEXT,
  -- Belt and braces for servers that send neither of the above: if the hash
  -- is unchanged there is nothing to re-parse or re-index.
  content_hash  BLOB,
  last_fetched  TEXT,
  -- The scheduler's only input: rows due now are the work list. A month
  -- from 1998 will never change again and can be pushed far out; the
  -- current month comes back quickly.
  next_fetch    TEXT,
  status        INTEGER,
  failures      INTEGER NOT NULL DEFAULT 0
) STRICT;

--;;

CREATE INDEX fetch_state_due ON fetch_state (next_fetch);

--;;

-- The durable, authoritative copy of parsed content. The Lucene index is
-- derived from this table and is disposable: losing the index costs a
-- rebuild, losing this costs a re-crawl of somebody else's servers.
CREATE TABLE document (
  id           INTEGER PRIMARY KEY,
  source_id    INTEGER NOT NULL REFERENCES source(id),
  kind         TEXT NOT NULL,
  -- The stable, cross-machine identity of a document. Two people who fetch
  -- the same archive independently agree on this and on nothing else --
  -- their id columns are unrelated. Anything shared between installations
  -- keys on url, never on id.
  url          TEXT NOT NULL UNIQUE,
  title        TEXT,
  body         TEXT,
  -- Set for versioned sources (documentation), null elsewhere.
  version      TEXT,
  published_at TEXT,
  author       TEXT,
  -- Threading. parent_id is the direct reply target, thread_id the root of
  -- the whole conversation; a root document points thread_id at itself so
  -- one query returns a whole thread without a recursive walk.
  parent_id    INTEGER REFERENCES document(id),
  thread_id    INTEGER REFERENCES document(id),
  -- JSON. Per-kind fields that do not deserve a column: patch attachments,
  -- commitfest status history, message headers.
  attrs        TEXT,
  content_hash BLOB,
  -- Null means the row has changed since it was last indexed, so this
  -- doubles as the indexer's work queue.
  indexed_at   TEXT
) STRICT;

--;;

-- Partial index: only the unindexed rows are in it, so it stays small even
-- when the table holds a decade of mail, and it empties itself as indexing
-- catches up.
CREATE INDEX document_pending_index ON document (id) WHERE indexed_at IS NULL;

--;;

CREATE INDEX document_thread ON document (thread_id);

--;;

-- Deliberately not indexed here: kind, version, author, published_at. Those
-- are search filters, and search is Lucene's job -- duplicating them as
-- SQLite indexes would slow every insert during a large fetch to serve
-- queries that never arrive at this layer. Add one when a query actually
-- needs it.

CREATE TABLE link (
  from_id INTEGER NOT NULL REFERENCES document(id),
  to_id   INTEGER NOT NULL REFERENCES document(id),
  kind    TEXT NOT NULL,
  PRIMARY KEY (from_id, to_id, kind)
) STRICT;

--;;

-- The primary key already serves traversal forwards. Related-document
-- navigation runs both ways -- a thread finds its commit and a commit finds
-- its thread -- so the reverse direction needs its own index.
CREATE INDEX link_to ON link (to_id);

--;;

-- Human relevance judgements: query, document, grade. This is the only
-- table whose contents are shared between installations, published with the
-- code and contributed to by other people, and that changes its shape.
--
-- It keys on document_url, not on a document id, because ids are assigned
-- locally: every user builds their own corpus, so id 4711 names a different
-- document on every machine while the URL names the same one everywhere.
--
-- There is deliberately no foreign key to document. A judgement is valid
-- for a document this installation has not fetched -- someone with a
-- five-year window still receives the judgements covering 1997, and they
-- become meaningful if they widen their range later.
--
-- judged_by is part of the key so two people can grade the same pair
-- independently, which is what makes inter-annotator agreement measurable.
CREATE TABLE judgment (
  corpus       TEXT NOT NULL,
  query        TEXT NOT NULL,
  document_url TEXT NOT NULL,
  -- Graded relevance on the usual four-point scale, 0 irrelevant to 3
  -- perfect; nDCG needs an ordered grade rather than a boolean.
  grade        INTEGER NOT NULL CHECK (grade BETWEEN 0 AND 3),
  judged_by    TEXT NOT NULL,
  judged_at    TEXT NOT NULL,
  PRIMARY KEY (corpus, query, document_url, judged_by)
) STRICT;

--;;

-- Evaluating one query means gathering every judged document for it.
CREATE INDEX judgment_query ON judgment (corpus, query);
