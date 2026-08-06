# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Goddinpotty is a static-site generator that turns a Logseq (formerly Roam Research) graph into a
published "digital garden" website, with privacy controls so only parts of the graph are exposed.
It's a fork of static-roam, now heavily diverged and Logseq-centric — Roam support is legacy and
likely broken. This is a personal tool (used to generate http://www.hyperphor.com/ammdi/), not a
general-purpose product; code quality varies and there are many `;;; TODO` / `;;; Status: not used`
comments that are accurate and worth reading before touching a namespace.

## Commands

```
lein test                    # run the test suite (includes a real build in generate-from-logseq)
lein run <config-file>       # generate a site from a config; defaults to resources/default-config.edn
lein run                     # same, using default config
bin/generate.sh              # bin/update.sh (forces Logseq to save via AppleScript, macOS-only) + lein run $GP_CONFIG
bin/local.sh                 # generate.sh with GP_DEV_MODE=true (adds dev-only features like links back to Roam/Logseq)
bin/serve.sh <port> <dir>    # serve a generated output directory locally and open it in a browser
```

To run a single test, use `lein test goddinpotty.templating-test` (namespace) or
`lein test :only goddinpotty.templating-test/test-name` style selectors, as usual for `clojure.test`.

Requires `nbb-logseq` on PATH (`npm install @logseq/nbb-logseq -g`) — the Logseq import pipeline shells
out to it (see Architecture below).

## Architecture

### Pipeline (see `goddinpotty.core/main`)

1. **Extract** — `import.logseq/produce-bm` shells out to `nbb-logseq`, running
   `resources/nbb-query.cljs` as a Datalog query against a live Logseq graph's Datascript DB
   (`nbb-query`/`nbb-extract`). This requires an actual Logseq instance to have the graph open and
   saved (`bin/update.sh` nudges Logseq to save first — fragile, macOS-only, documented as broken on
   newer macOS). Raw extraction is snapshotted to `nbb-extract.edn` for debugging/replay.
2. **Build the block-map ("bm")** — `database.clj` (`build-db-1`, `generate-inverse-refs`) converts
   the raw nbb datoms into the central data structure: a flat map from block-id to a "block" record
   (content, parent/children, refs, tags, etc). Almost everything downstream operates on this `bm`.
   `parser.clj` parses each block's raw text (Roam/Logseq markup, via an Instaparse grammar in
   `resources/parser.ebnf`) into an intermediate AST.
3. **Determine inclusion** — `batadase.clj` ("database" accessors, deliberately mis-spelled — see
   its docstring, a protest against Clojure namespace friction) computes `:include?`/`:display?` per
   block by walking the graph from **entry tags** (default `#EntryPoint`) until it hits **exit tags**
   (default `#ExitPoint`/`#Private`), implementing the privacy model described in README.md. This is
   the load-bearing logic for "what gets published" — read it before changing inclusion behavior.
4. **Generate derived pages** — `core/add-generated-pages` adds the index page (`index.clj`, an
   ag-grid-based page) and the force-directed graph/map page (`graph.clj`, Vega via `oz`).
5. **Render** — `rendering.clj` turns the parsed AST into Hiccup; `templating.clj` wraps rendered
   block content into full page templates (nav, sidebar, colophon, twin-pages widget, etc — there's
   acknowledged overlap between templating.clj and html-generation.clj, treat them as one layer);
   `html-generation.clj` writes Hiccup out as HTML files, plus builds the client-side `search.js`
   index (`search.clj`) and copies static assets.
6. **Publish** — output is built into a temp directory then atomically swapped into the configured
   `:output-dir` (`core/output-bm` → `replace-directory`). `post-generation` (Logseq-specific) then
   copies referenced local images into the output (Logseq stores images in-repo, not cloud-hosted,
   so goddinpotty has to figure out which images are actually used and publish just those).

### Supporting namespaces

- `config.clj` — Aero-based config (`resources/default-config.edn` documents every key; a graph-specific
  config `#include`s it and overrides). Loaded once into an atom via `config/set-config-path!` /
  `set-config-map!`; access via `(config/config :some-key)`.
- `context.clj` — thin dynamic-binding helper that attaches debugging context to `ex-info` exceptions.
- `endure.clj` — disk-backed memoization (via the `enduro` lib) for expensive computations, keyed
  under `.enduro.d/`.
- `curation.clj` — REPL-only helper functions for one-off graph curation/cleanup; not wired into the
  main pipeline and not held to the same quality bar.
- `import/edit_times.clj`, `import/roam_images.clj`, `import/roam.clj`, `import/logseq_from_md.clj` —
  alternate/legacy import paths (raw Roam JSON export, parsing Logseq markdown directly instead of via
  nbb) and edit-time tracking.
- `convert/roam_logseq*.clj` — a one-time Roam→Logseq migration tool ("RoamAway"), including a Seesaw
  (Swing) GUI; largely independent of the main generator pipeline.
- `export/*.clj` — exporters to other formats/services (Markdown, GDF graph format, Mastodon posting).
- `wayback.clj`, `twin_pages.clj` — integrations with external services (Wayback Machine archiving,
  a "TwinPages" backlink widget).
- `dotty.clj`, `spelling.clj` — experimental/unused (Graphviz export, LanguageTool spellcheck API);
  marked in-file as not currently used.

### The "bm" (block-map) convention

Most functions across the codebase take a `bm` (or `block-map`) as an argument — the full graph as a
map of block-id → block. There's no single shared session state object beyond this; `core/last-bm` is
an atom holding the most recently built `bm`, used for REPL-driven exploration (`core/get-page`,
`gen-page`, `find-pages`, etc.) after running `main`.

### Config

A config file is EDN read via `aero` (supports `#include`, `#env`, `#or`, and a custom `#split`
reader). Real-world configs typically `#include "default-config.edn"` and override `:source`,
`:output-dir`, `:entry-tags`, etc. See `resources/default-config.edn` for the full set of keys and
`resources/test/logseq-test-config.edn` for a working example used by the test suite.
