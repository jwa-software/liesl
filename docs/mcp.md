<!-- Copyright (c) 2026 Junzhe Wang, licensed under the MIT License. -->

# Using liesl from Claude Code

liesl is an MCP server: Claude Code starts it as a child process, talks to it over stdin and stdout, and gets one tool, `search`. This page is how to switch it on, either for one session at a time or always in the clone.

## Before the first search

The server answers from the index under `data/`, so the corpus has to be fetched, parsed and indexed once. From the clone:

```
clj -X:migrate
clj -X:fetch :corpus fhir
clj -X:parse :corpus fhir
clj -X:link :corpus fhir
clj -X:index
```

`:versions '["R4"]'` on the fetch and the parse limits them to one version, about 210 MB instead of 1.2 GB. `clj -X:search :q birthTime :version R4 :limit 3` checks the result from the shell without Claude Code in the way.

## Opt-in: one session at a time

Write a small JSON file anywhere outside the clone, say `~/liesl-mcp.json`, naming the launcher script by its absolute path:

```json
{
  "mcpServers": {
    "liesl": {
      "command": "/absolute/path/to/liesl/bin/mcp"
    }
  }
}
```

Then start Claude Code with that file whenever liesl should be there, from any directory:

```
claude --mcp-config ~/liesl-mcp.json
```

A plain `claude` has no liesl. Adding `--strict-mcp-config` makes that session use only the servers in the file. A shell alias keeps it short:

```
alias claude-fhir='claude --mcp-config ~/liesl-mcp.json'
```

`bin/mcp` exists because `clojure` finds `deps.edn` only in its working directory, and Claude Code starts the server wherever it was started itself. The script changes into the clone and then runs `clojure -M:mcp`.

## Always on, in the clone

To have liesl in every session started in the clone, and nowhere else, register it once from there:

```
cd /path/to/liesl
claude mcp add liesl -- clojure -M:mcp
```

`claude mcp list` shows it; `claude mcp remove liesl` takes it out again. Registration keeps only the command, so a change to liesl's code is picked up the next time Claude Code starts.

## The tools

`search` takes `q`, the question in Lucene syntax (bare words, quoted phrases, `field:value`), and optionally `version` (`STU3`, `R4`, `R4B`, `R5`), `kind` (`spec`) and `limit` (10 when absent). Each hit comes back as three lines: the page's URL, its version and title, and the passage that matched. A question Lucene cannot parse comes back as an error result with the parser's message.

`get` takes `url`, as a search hit gave it, and optionally `limit`, the most characters of the page to return (20,000 when absent). It returns the citation, the URL on the first line and the version and title on the second, then a blank line and the page's text as it was indexed, one line per heading, paragraph, list item or table cell. A page longer than the limit is cut there and ends with a line saying `[cut at 20000 of 1839001 characters]`. An unknown URL is an error result naming it.

`related` takes `url` and returns the same page in the other published versions, one citation per counterpart: its URL, then its version and title. A page that exists in one version only answers `No other versions.`, which is itself an answer: the R4 `birthTime` extension has counterparts in STU3 and R4B and none in R5, because R5 moved extensions out of the core specification. The counterparts come from the `link` table, which `clj -X:link` fills; without that step every page reports no other versions. Unlike the index, the edges are read from the database per call, so a `clj -X:link` while the server runs is seen at once.

## Two things to know

**The index is read once, at startup.** After `clj -X:index` the running server still sees the old index; reconnect with `/mcp` inside Claude Code, or start a new session.

**stdout is the protocol.** The server never prints there. What it has to say, including Lucene's one-line warning about the vector API at startup, goes to stderr, which Claude Code shows under `/mcp` when a server fails to connect.
