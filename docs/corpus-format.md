<!-- Copyright (c) 2026 Junzhe Wang, licensed under the MIT License. -->

# The corpus definition, `corpus.edn`

A corpus is one subject's sources bundled under a name: for FHIR, the specification site and the repository it is built from. Everything liesl knows about a subject comes from one file, `corpora/<name>/corpus.edn`; the engine itself never mentions FHIR or any other domain. This page is how to write that file.

## Where it lives and how it is found

The file sits at `corpora/<name>/corpus.edn`, and `<name>` is how the corpus is addressed everywhere: `(liesl.corpus/load "fhir")` reads `corpora/fhir/corpus.edn`. `corpora/` is on the classpath (it is one of the `:paths` in `deps.edn`), so the loader asks the classpath for `fhir/corpus.edn` rather than the filesystem, and a corpus is found from wherever liesl is run.

The `:corpus` key inside the file **must equal the directory name**. A file that says `:corpus "fhir2"` in a directory called `fhir` is refused, because the same corpus would otherwise answer to two names.

## It is EDN, read by `clojure.edn`

The file is EDN: maps, vectors, strings, keywords, numbers, and `;;` comments. It is read with `clojure.edn/read-string`, never with the full Clojure reader, so it **cannot run code**. In particular the `#=` reader form is rejected, and a file containing one fails to load rather than evaluating anything. That is deliberate: a corpus package is data, and cloning a repository with one in it must be safe.

Every file the repository authors opens with the license line, as a comment:

```clojure
;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.
```

## The top level

| Key | Required | Meaning |
|:---|:---|:---|
| `:corpus` | yes | The corpus name; must equal the directory. |
| `:sources` | yes | A vector of source maps, described below. May be empty. |
| `:versions` | no | The corpus's published versions as strings, **oldest first**. Version strings do not sort -- alphabetically `STU3` comes after `R5`, and it is the oldest -- so this vector is the only statement of their order. |

Any other top-level key is ignored.

## A source

Each entry of `:sources` is one origin within the corpus: a site to fetch, a repository to clone. It becomes one row of the `source` table (see `schema.md`), written by `liesl.corpus/upsert-sources!` and updated in place on a re-run, so a source keeps its id when the file changes.

| Key | Required | Meaning |
|:---|:---|:---|
| `:name` | yes | The source's name within the corpus, e.g. `spec`, `repo`. Unique within the corpus. |
| `:kind` | yes | What sort of thing the source produces; selects the parser. Free text. |
| `:base-url` | yes | Where fetching starts. Stored as `base_url`. |
| `:config` | no | A map of whatever this source needs. Stored as EDN text in `source.config` and handed back to the code that reads it. |

### What the engine reads from `:config`

The engine does not interpret `:config` in general; a key it does not know is kept, not rejected. Three keys it does read, for a source published as one archive per version:

| Key | Meaning |
|:---|:---|
| `:version-path` | The path from `:base-url` to one version's directory, with the literal text `{version}` where the version string goes. |
| `:archive` | The archive's file name inside that directory. |
| `:parser` | The function that turns one fetched archive into documents, as a namespace-qualified symbol, e.g. `fhir.parsers.spec/documents`. Without it the source is fetched but never parsed. |

`liesl.fetch/fetch-archive!` builds the URL as `:base-url` + `:version-path` with `{version}` replaced + `:archive`, and stores the download at `<archives directory>/<corpus>/<source name>/<version>/<archive>`. So for the FHIR specification, version `R4`:

```
https://hl7.org/fhir/  +  R4/  +  fhir-spec.zip  ->  https://hl7.org/fhir/R4/fhir-spec.zip
```

`{version}` is plain text, replaced by a string substitution -- nothing checks that it is present, so a `:version-path` without it gives every version the same URL. Spell it exactly `{version}`.

### The parser

`liesl.parse` resolves the `:parser` symbol by name, loading its namespace from `corpora/` on the classpath, and calls it once per version with three arguments: the archive file on disk, the version string, and the URL prefix `:base-url` + `:version-path` with `{version}` replaced, e.g. `https://hl7.org/fhir/R4/`. It must return a sequence of maps, each `{:url <string, unique across the corpus> :title <string or nil> :body <string>}`; the engine adds the source's `:kind` and the version and writes each as a `document` row through `liesl.document/upsert!` (see `schema.md`). Return the whole sequence rather than a lazy one that still reads the archive: the engine writes the rows after the call returns.

`clj -X:parse :corpus fhir`, optionally `:versions '["R4"]'`, runs this for every source naming a parser. It refuses a version whose archive is not on disk -- `<source name> <version> has not been fetched: no <file>` -- so `clj -X:fetch` comes first, and a symbol that names nothing -- `<source name> config names a parser that does not exist: <symbol>`.

## What goes wrong, and what it says

Every failure is an exception whose message says what was wrong and whose data says where. The data always carries `:corpus` and `:resource` (the classpath path that was looked up), plus:

| Message | Extra data | Cause |
|:---|:---|:---|
| `No such corpus on the classpath` | -- | No `corpora/<name>/corpus.edn`, or `corpora/` is not on the classpath. |
| `Corpus definition is not a readable EDN` | the reader's own error as the cause | A syntax error, or a `#=` form. |
| `Corpus definition is not a map` | `:type`, what was read instead | The file's top-level form is a vector, a string, anything but a map. |
| `Corpus definition is missing required keys` | `:missing-keys`, `:found-keys` | `:corpus` or `:sources` absent. |
| `Corpus <declared> does not match its directory <name>` | -- | `:corpus` disagrees with the directory. |
| `Corpus source is missing required keys` | `:source`, the offending map; `:missing-keys`, `:found-keys` | A source without `:name`, `:kind` or `:base-url`. |

`fetch-archive!` adds two of its own, raised when it is asked to fetch a source whose config lacks a key: `<source name> config needs :version-path` and `<source name> config needs :archive`.

## A complete example

`corpora/fhir/corpus.edn` as it stands, minus its closing comment:

```clojure
;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

{:corpus "fhir"

 ;; Oldest first. Version order lives here because the strings do not sort:
 ;; alphabetically STU3 comes last, and it is the oldest of the four.
 :versions ["STU3" "R4" "R4B" "R5"]

 :sources
 [{:name     "spec"
   :kind     "spec"
   :base-url "https://hl7.org/fhir/"
   ;; Each version is published whole as one archive at base-url + version
   ;; path + archive. A published version never changes, so once fetched its
   ;; next_fetch moves years out; only the current version is worth revisiting.
   :config   {:version-path "{version}/"
              :archive      "fhir-spec.zip"
              ;; Turns one fetched archive into document maps; resolved by name.
              :parser       fhir.parsers.spec/documents}}

  {:name     "repo"
   :kind     "source"
   :base-url "https://github.com/HL7/fhir.git"
   ;; Cloned, not crawled. Around 800 MB, hence shallow.
   :config   {:clone :shallow}}]}
```

The `repo` source shows a config the engine does not read: `:clone :shallow` is kept in the row as written, for whatever handles that kind of source. It is not an error, and it is not an archive source, so `fetch-archive!` would refuse it with `repo config needs :version-path`, and `clj -X:parse` skips it because it names no parser.

## Writing a second corpus

Make `corpora/<name>/`, write `corpus.edn` with `:corpus "<name>"`, at least one source with the three required keys, and `:versions` if the subject has them. Load it in a REPL with `(liesl.corpus/load "<name>")`; the exception, if any, names the problem. Then `(liesl.corpus/upsert-sources! conn (liesl.corpus/load "<name>"))` writes its source rows. For an archive source, write its parser under `corpora/<name>/parsers/` with the three-argument shape above and name it in `:config` as `:parser`; then `clj -X:fetch :corpus <name>` and `clj -X:parse :corpus <name>` fill the `document` table.
