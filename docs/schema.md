<!-- Copyright (c) 2026 Junzhe Wang, licensed under the MIT License. -->

# The data model

liesl keeps everything it fetches and parses in one SQLite database, `data/liesl.db` under the project root unless `LIESL_DB` says otherwise. This page is the reference for what is in it: every table, every column, and why each exists. The schema itself is defined once, in `resources/migrations/`, and applied with `clj -X:migrate`. If this page and the DDL ever disagree, the DDL is right and this page has a bug.

## Rules that hold for every table

**Every table is `STRICT`.** SQLite normally treats a column's type as advice and stores whatever it is given; a `STRICT` table rejects a value of the wrong type instead. `STRICT` allows only `INT`, `INTEGER`, `REAL`, `TEXT`, `BLOB` and `ANY`, which is why timestamps and structured data are `TEXT`.

**Timestamps are ISO-8601 in UTC**, for example `2026-09-05T16:21:12Z`, stored as `TEXT`. That format sorts correctly as text, so range queries and `ORDER BY` need no conversion, and it stays readable in the `sqlite3` shell.

**Foreign keys are enforced only if the connection asks.** SQLite's `PRAGMA foreign_keys` is off by default and is per connection, so every `REFERENCES` clause below does nothing until a connection turns it on. `liesl.db/get-connection` does that; a connection opened any other way accepts rows that point at nothing.

**Ids are local, URLs are shared.** Every installation builds its own corpus, so `document.id` 4711 names a different document on every machine. `document.url` names the same one everywhere. Anything meant to be shared between installations, such as the judgment set, keys on the URL and never on an id.

## `source` -- one row per origin within a corpus

A corpus such as `fhir` draws on several origins: a specification site, a source repository, a chat archive. Each is a source, declared in the corpus package's `corpus.edn` and written here by `liesl.corpus/upsert-sources!`, which updates an existing row in place so ids survive a re-run.

| Column | Type | Meaning |
|:---|:---|:---|
| `id` | `INTEGER` | Local row id; the primary key. |
| `corpus` | `TEXT` | The corpus this source belongs to, e.g. `fhir`. |
| `name` | `TEXT` | The source's name within its corpus, e.g. `spec`, `repo`. |
| `kind` | `TEXT` | What sort of thing the source produces; selects the parser. Free text with no constraint, so the engine never has to learn a domain. |
| `base_url` | `TEXT` | Where fetching starts. |
| `config` | `TEXT` | Whatever the corpus package needs to drive its own fetching, stored as EDN text and read back with `clojure.edn/read-string`. The engine passes it through without interpreting it. The DDL comment on this column says JSON; EDN is what is actually written. |

Constraints: `UNIQUE (corpus, name)`, the key the upsert matches on.

## `fetch_state` -- crawl bookkeeping, one row per URL

Kept separate from `document` because one fetched page can produce hundreds of documents, and a URL that produced none still has to be remembered so it is not fetched again. Written by `liesl.fetch/fetch-url!` after every request.

| Column | Type | Meaning |
|:---|:---|:---|
| `url` | `TEXT` | What was fetched; the primary key. |
| `source_id` | `INTEGER` | The source it belongs to; references `source.id`. |
| `etag` | `TEXT` | The `ETag` the server sent, if any. Sent back as `If-None-Match`, so an unchanged page answers 304 with no body. |
| `last_modified` | `TEXT` | The `Last-Modified` the server sent, if any. Sent back as `If-Modified-Since`. |
| `content_hash` | `BLOB` | SHA-256 of the last body received. Change detection for servers that send neither validator. |
| `last_fetched` | `TEXT` | When the URL was last requested. |
| `next_fetch` | `TEXT` | When it is worth requesting again; the scheduler's only input. A published specification version never changes and can be pushed years out, while the current version comes back sooner. Set by the caller, not by the fetcher. |
| `status` | `INTEGER` | The HTTP status of the last request, as it came back. |
| `failures` | `INTEGER` | Consecutive failures, counting any status of 400 or above; reset to 0 by a success. Input for backing off. |

Indexes: `fetch_state_due` on `next_fetch`, so the work list -- rows due now -- is one range scan.

Null `etag`, `last_modified` and `status` are normal for a source that is cloned or read from disk rather than fetched over HTTP; the row still carries the identity, the hash and the schedule.

## `document` -- the durable copy of parsed content

The unit of retrieval: one specification page, one commit, one chat message. This table is the authority; the search index is derived from it and can be rebuilt from it at any time. Losing the index costs a rebuild; losing this table costs a re-crawl of somebody else's servers.

| Column | Type | Meaning |
|:---|:---|:---|
| `id` | `INTEGER` | Local row id; the primary key. Differs on every machine and is never shared. |
| `source_id` | `INTEGER` | The source that produced it; references `source.id`. |
| `kind` | `TEXT` | What sort of document, e.g. `spec`, `commit`, `chat`. |
| `url` | `TEXT` | The stable identity: unique, and the same on every machine. It need not be an HTTP URL, but every document must have one. |
| `title` | `TEXT` | The title, where the source has one. |
| `body` | `TEXT` | The text; what gets indexed. |
| `version` | `TEXT` | Which version of the corpus the document belongs to, e.g. `R4`; null for an unversioned source. Version order is not alphabetical and comes from `corpus.edn`, never from this string. |
| `published_at` | `TEXT` | When the document was published, ISO-8601 UTC. |
| `author` | `TEXT` | A single name where the source has one; null otherwise. |
| `parent_id` | `INTEGER` | The document this one is contained in or responds to: a section's page, a reply's target. References `document.id`. |
| `thread_id` | `INTEGER` | The root of that tree; a root points at itself. Denormalized so one `WHERE thread_id = ?` returns a whole tree without a recursive walk. References `document.id`. |
| `attrs` | `TEXT` | JSON. Per-kind fields that do not deserve a column of their own. |
| `content_hash` | `BLOB` | Hash of the content as parsed, for change detection. |
| `indexed_at` | `TEXT` | When the row was last indexed. Null means it has changed since, so the column doubles as the indexer's work queue. |

Indexes: `document_pending_index`, a partial index over rows where `indexed_at IS NULL`, which stays small and empties itself as indexing catches up; `document_thread` on `thread_id`. `kind`, `version`, `author` and `published_at` are deliberately not indexed here: they are search filters, and search is the index's job, not SQLite's.

## `link` -- edges between documents

The same page in two versions, a commit and the discussion that led to it, a section and the page it belongs to: relations between documents are rows here, not columns on `document`.

| Column | Type | Meaning |
|:---|:---|:---|
| `from_id` | `INTEGER` | One end; references `document.id`. |
| `to_id` | `INTEGER` | The other end; references `document.id`. |
| `kind` | `TEXT` | What the edge means, e.g. `version-of`, `discussed-in`, `changed-by`. |

The primary key is all three columns, so the same pair of documents may be joined by several kinds of edge. `link_to` indexes `to_id`, because navigation runs both ways and the primary key only serves the forward direction.

## `judgment` -- human relevance grades

The only table whose contents are shared between installations: a set of query, document and grade triples, published with the code and contributed to by other people, that measures whether a ranking change helped.

| Column | Type | Meaning |
|:---|:---|:---|
| `corpus` | `TEXT` | Which corpus the query was asked of. |
| `query` | `TEXT` | The query as typed. |
| `document_url` | `TEXT` | The document, by URL and not by id, because ids are local. There is deliberately no foreign key to `document`: a judgement stays valid for a document this installation has not fetched. |
| `grade` | `INTEGER` | Relevance on the usual four-point scale, 0 irrelevant to 3 perfect; `CHECK (grade BETWEEN 0 AND 3)`. nDCG needs an ordered grade rather than a boolean. |
| `judged_by` | `TEXT` | Who graded it. Part of the key, so two people can grade the same pair and their agreement can be measured. |
| `judged_at` | `TEXT` | When, ISO-8601 UTC. |

The primary key is `(corpus, query, document_url, judged_by)`. `judgment_query` indexes `(corpus, query)`, because evaluating one query means gathering every judged document for it.
