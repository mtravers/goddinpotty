Write a blygger interface. 

# initial spec

Use the spec in /opt/reposed/blygger-spec/docs/protocol-v0.2.md

First cut.
have a /blyg subdirectory under ammdi or whatever root.

Blocks tagged with #blyg become blygger entries.

Needs to be a way to update this without doing a full rebuild (should be easy)

Eventually, automate the publication of new pages etc.

# claude notes

Implemented as `goddinpotty.export.blygger`, wired into `core/output-bm` (runs into the
temp dir before the atomic swap into `:output-dir`, so it can't ship a build that
silently drops previously-published items) plus a standalone `core/refresh-blyg!` /
`bin/blyg-update.sh` path that re-extracts from Logseq but skips full HTML
generation/search/graph — the cheap "update" path the spec asked for.

Config: new `:blygger` key in `resources/default-config.edn` (disabled by default —
turning it on starts a real, subscribable feed at `:real-base-url`, so it's an
explicit per-graph opt-in, not a default-on feature). See that file for the keys.
`resources/ammdi-config.edn` has it wired but `:enabled? false` until the state
file exists and this has been reviewed; `resources/ammdi-private.edn` (the local
`:unexclude?` preview profile) force-disables it — see below.

## Scope (v1 / first cut)

Implements protocol-v0.2 L1 publish side for **fragments only**:
`blyg.json`, `feed.xml`, `items/index.json`, `items/{id}.json`, an archive/home
page at the mount root (`blyg/index.html`), and a permalink page per item at
`f/{id}/`. Both HTML pages reuse the site's own `templating/page-hiccup` (nav,
fonts, css, search widget) rather than serving bare unstyled markup, so `/blyg`
reads as part of the same site; the fragment-card layout (content, "Created
.../vN" line, permalink) takes cues from reference blyg clients like
blyg.aneeshsathe.com without their bespoke masthead art. Explicitly **not**
implemented, in order of likely next-ness:

- **Threads / transclusion** (§10) — no `![[id]]` grammar support. Everything
  publishes as `kind: "fragment"`.
- **Pins** (§8) — no `items/{id}/v{n}.json`, no pin citations. All history
  beyond the latest version stays withheld, per protocol default.
- **Blogroll** (§11) — no `blogroll.opml`.
- **Generation provenance** (§5.7) — no `generated` array. (Everything here is
  human-authored Logseq content; revisit if that changes.)
- **`media[]`** — always emitted as `[]`. Images ride along inline in
  `content_html`/feed descriptions instead (already-relocated by the normal
  site build, then absolutized against `:real-base-url`), which satisfies the
  feed's self-containment requirement but doesn't give readers the structured
  media list §5.4 describes. Fine for now; would need real work to do properly
  (media objects are supposed to be immutable-forever once published, which
  this doesn't track at all).
- The reference client's fuller presentation layer (masthead art, version-nav
  carousel, thread views, css-contract.md) is that client's promise, not the
  protocol's — not replicated. Archive/permalink pages here are plain
  site-styled cards, no pagination on the archive page (fine at personal-blog
  scale; would need one past a few hundred items). No search widget on these
  two pages — search.js's result links assume the page loaded from
  `output-dir` root, which isn't true for anything nested under `blyg/`.

`page-hiccup`'s asset/nav hrefs (`assets/default.css`, page-links like
`About`) are relative, correct only for normal pages living directly at
`output-dir/<title>`. Blyg pages nest one or two directories deeper
(`blyg/`, `blyg/f/{id}/`), so those same relative hrefs would resolve to the
wrong place — `write-surfaces!` runs the whole rendered page through
`absolutize-html` against `:real-base-url` before writing (`site-page-html`),
same fix `content_html` already needed. The two blyg-relative links on the
archive page (`feed.xml`, `blyg.json`) are built from `origin` (the blyg
mount URL) rather than left relative, so blanket absolutization doesn't
strip their `blyg/` prefix.

## Tag convention

`#blyg` must be **inline in the tagged block's own text** (detected via
`bd/block-hashtags`, same as `:hover-tags`) — *not* the "tag in a contained
child block" convention `#Private`/`#ExitPoint` use (`bd/tagged?`). The tagged
block plus its (non-excluded) children, recursively, become one fragment's
body.

**Source-page attribution.** If the tagged block belongs to a normal content
page (`bd/block-page`) that isn't a journal/daily-notes entry (journal pages
have no meaningful identity of their own to link to) and that page is itself
published (`bd/displayed?` — otherwise the link would be dead, or worse,
private), the item gets a one-line attribution prepended: `"From [Page
Title](url)"` in `content_md`, a `<p class="source-page">` in `content_html`.
Journal-page items get none. This line is **part of `content_md`**, so it's
covered by the content hash below — turning it on for the first time bumps
the version of every already-published content-page item (a real content
change, not a phantom one).

**`#blyg` is its own publication decision, deliberately separate from
`bd/included?`/`bd/displayed?`.** Those track whether the site's
entry-tag graph walk would generate the block a page — a block on a page
nothing links to (no `#EntryPoint` reaches it) is `:include? false` there
purely because it's *unreachable*, which has nothing to do with whether the
author wants it public. `#blyg` acts as its own entry point: tagging a block
publishes it via blyg even if it never gets a page of its own. The one thing
that still gates it is an **explicit exit tag** (`#Private`/`#ExitPoint`/etc,
checked directly via `bd/privacy-exit-point?` — the block's own tags plus its
page-hierarchy ancestors, not the whole-graph reachability computation) —
that's the actual privacy boundary in this codebase, and `#blyg` does not
override it. A `#blyg` block skipped for this reason is logged, same as
before.

**Not `bd/exit-point?`** — that function also folds in `:excluded?`,
database.clj's journal/daily-notes performance-skip flag (set on every block
of a journal page when `:daily-notes?` is false, purely so the site's BFS
graph walk doesn't bother traversing pages it'll never display). That flag
means "the main site wouldn't show this anyway", not "the author marked this
private" — treating it as a privacy signal silently dropped every `#blyg`
block written on a journal page. `bd/privacy-exit-point?` is the same
tag-lookup logic with that fold removed; `blyg.clj`'s `excluded?` uses it, not
`exit-point?`.

Since `bd/privacy-exit-point?` is a structural check (parent-chain + tag
lookups), independent of `:include?`/`:depth`/`:unexclude?`, this is unaffected by the
`:unexclude?` private-preview flag either way. Still, `ammdi-private.edn`
disables blyg outright, so a throwaway local build never touches the shared
state file.

## The state file — the one irreplaceable artifact

`:blygger :state-file` (required, absolute path) holds an EDN map,
block-uuid → `{:blyg-id :kind :created :updated :version :content-md
:content-html :content-hash :changelog}`. The block-uuid ↔ blyg-id mapping and
version/changelog history **cannot be reconstructed** from the published
surface, so this file must live somewhere durable and git-tracked, and
explicitly **not** under `:output-dir` — `core/output-bm` deletes the old
output-dir wholesale on every full build (`replace-directory` →
`rename-dirs`), so anything not freshly written into that run's temp dir is
gone. For ammdi, it's configured at `/opt/mt/repos/ammdi/.blyg-state.edn` —
inside the Logseq source repo, not the generated-output repo. **Make sure
that path is actually tracked and gets committed** in that repo; goddinpotty
doesn't do that for you.

Every build regenerates every `items/{id}.json` file (including withdrawn
ones) from the *full* accumulated state, not just this run's candidates —
that's what makes "200 forever" (§4) hold across builds without needing to
diff against whatever happened to survive in `:output-dir`.

## Version-bump policy

`content_hash` (sha256 of `content_md`, per spec) is the only thing that
triggers a version bump — republishing with unchanged content is a no-op
(`next-entry` returns the existing entry unchanged). This means:

- `content_md` must be a **stable, deliberate** function of the source block —
  see `parsed->blyg-md`, a cut-down cousin of `export.markdown/parsed->markdown`
  that strips the `#blyg` tag itself and points internal links at
  `:real-base-url` instead of sibling `.md` files. Changing this function's
  output for existing content **will** bump every affected item's version and
  flood the feed with phantom edits — treat it like a wire format.
- `content_html` is cached in the state file but is *not* part of the hash —
  it's derived from the same parsed tree via the real site renderer
  (`r/block-hiccup`, tag node blanked out first so images/aliases/embeds/
  headings all render normally) plus `absolutize-html`. If a rendering-only
  bug fix needs to reach already-published items without bumping their
  version, use `blyg/reseal!` (re-renders `content_html` for every
  non-withdrawn item in place).
- No authored "note" support yet (Logseq has no natural place to put one) —
  every changelog entry carries `:note nil`.

## Withdrawal is opt-in, not automatic

If a previously-published item's block is no longer tagged (or no longer
included/displayed), `publish!` **logs a warning and leaves it alone** by
default. Pass `:withdraw? true` explicitly to actually withdraw. Rationale: a
degraded nbb extraction (Logseq hadn't saved, a config typo, whatever) looks
identical to "author removed the tag" from inside the bm — and withdrawal is
supposed to be a deliberate authorial act, not a side effect of a flaky
build. `core/refresh-blyg!` and `core/blyg-publish!` both take `:withdraw?`/
`:dry-run?` kwargs.

## Mount path vs. the tag's own page

The default `:tag`/`:mount` pair (`"blyg"` / `"blyg/"`) guarantees a collision: if any
displayed block ends up referencing the `#blyg` tag, the site's normal page generator
(`html-generation/generate-content-page`) writes that tag's own backlink page
**extensionless** at `output-dir/blyg` -- same path as the mount root, just without the
trailing slash. `write-surfaces!`'s `ensure-mount-dir!` detects a non-directory file
already sitting at the mount root and deletes it before writing the blyg surface -- the
export directory always wins. If you pick a custom `:mount`, be aware it can collide the
same way with *any* real page whose title happens to match; this only guards the mount
root itself, not arbitrary path collisions further down (eg `items/` or `f/`, which are
vanishingly unlikely to match a real page title).

## Known gaps / TODO

- **No `<link rel="blyg">` on site pages.** §12.1 step 4 (resolution) says a
  publisher whose blyg mounts away from the front page SHOULD emit
  `<link rel="blyg" href="…">` on pages it expects shared, so a reader handed
  any page URL can discover the feed. Without it, the only way to find
  `/blyg/` is to already know the URL. This is the biggest real gap in "does
  this actually work as a blyg" terms, and it's deliberately not done here:
  it means touching `templating.clj`'s shared page head, which every page on
  the site goes through, for a feature that's still `:enabled? false`. Do
  this before actually pointing anyone at the feed.
- `publish!`/`reseal!` write surfaces to disk *before* saving state (not
  after) — if a run crashes partway through `write-surfaces!`, an unsaved
  state means the next run recomputes the same items as "changed" and
  rewrites them (a harmless phantom version bump). The other order is worse:
  state would say "done" while some files never made it to disk, and the
  next run's unchanged-hash check would skip rewriting them forever. Neither
  `publish!` nor `refresh-blyg!` (the standalone/no-full-rebuild path) is
  otherwise transactional the way a full `core/output-bm` build is (temp dir
  + atomic swap) — this ordering is the cheap substitute, not a real fix.
- `test/goddinpotty/export/blygger_test.clj` covers the round trip (publish →
  no-op → edit → bump → untag → warn → `:withdraw? true` → endcap) plus the
  privacy gate and `:dry-run?`, against hand-built in-memory blocks (like
  `rendering-test`'s `fake-block`) rather than a real Logseq graph — driving
  an actual `#blyg` block through the real nbb/Logseq extraction pipeline
  (`resources/test/logseq-test-config.edn`) would need editing the live
  "logseq-test" graph in the Logseq app and forcing a save, which is exactly
  the flaky `bin/update.sh` path CLAUDE.md already flags as broken on modern
  macOS — not a reliable place to anchor a test.
- CORS (`Access-Control-Allow-Origin: *`, §4) for `/blyg/*.json` isn't
  goddinpotty's problem to solve (it's a static-file generator) but is a
  precondition for anyone actually reading the feed cross-origin — needs a
  server config change (the untracked `resources/.htaccess` is presumably the
  hook, if serving via Apache).

## The mount is the site's first real directory

Every other page is a flat, extensionless file at `output-dir` root, so a
request for it is never ambiguous to Apache. `blyg/` is the first genuine
subdirectory goddinpotty has ever published, and for `ammdi.hyperphor.com`
that interacts badly with how the subdomain is set up: `hyperphor-git/.htaccess`
maps `ammdi.hyperphor.com/*` into `/ammdi/*` on the *same* docroot via an
internal `RewriteRule` (no `[R]`), keeping the address bar on the subdomain —
this is invisible for flat-file pages. But a bare request for `/blyg` (no
trailing slash) resolves to a real directory, which triggers `mod_dir`'s
automatic slash-redirect; that redirect is built from the already-rewritten
path on the canonical server name, so it leaks `hyperphor.com/ammdi/blyg/`
instead of staying on the subdomain.

**Tried and reverted:** a `.htaccess` with `DirectorySlash Off` written into
the mount root on every build, meant to suppress that redirect entirely
(Apache docs say it should still serve `DirectoryIndex` directly for the bare
path). Deployed to production and broke `/blyg` outright (404) — reasoning
about NFSN's actual Apache behavior from docs alone wasn't good enough, and
this combines with the root `.htaccess`'s own `RewriteRule` in a way that
wasn't fully understood before shipping it. Reverted both the live
`ammdi/blyg/.htaccess` and the `write-mount-htaccess!` call that generated
it. **Do not re-add `DirectorySlash Off` (or any other `.htaccess` tweak in
the mount) without testing against a real request to the live host first** —
the ugly-but-working redirect to `hyperphor.com/ammdi/blyg/` for the bare
path is the current accepted behavior; only `/blyg/` (with slash, which is
what every link goddinpotty emits actually uses) needs to work cleanly, and
it does.

# Stage 1.5

## Adapt to blygger protocol 0.3

## Thread support

For now, minimal – I want to be able to publish a thread, eg of my 4 points on the Blygger page. I guess if there is a block tagged #blyg with subblocks, make ti a thread?

TODO links to pages should be to the #blyg block (with #id or whatever`)

## Claude thoughts

Read protocol-v0.3.md (`/opt/reposed/blygger-spec/docs/`) against what's implemented.
Two mostly-independent pieces of work, worth two separate PRs in this order.

### Part A: wire-version bump (small, mechanical, do first)

0.3's revision history says L1 "carried forward here unchanged in substance" from
0.2 — same-origin threads included. The real substance of 0.3 is L2: cross-origin
transclusion, stubs, lineage, Webmention. None of that is in scope; we stay an L1
publisher. What's actually needed:

- `"blyg"` → `"0.3"` in manifest, item JSON, and (implicitly, no wire field) the feed.
- Manifest: add `generator_url` (SHOULD emit per §6.1) — one absolute URL, hardcode to
  this repo's GitHub URL. `level` stays `1` (see below).
- Item JSON: add `"page"` (§5.8, new in 0.3) — the item's permalink path, which for us
  is exactly `f/{id}/` already. Cheap, and closes a real gap (§5.8: "readers that
  built links to remote items had to guess at a convention"). Also add
  `<link rel="alternate" type="application/json" href="{origin}items/{id}.json">`
  to the permalink page head (`permalink-page-hiccup`'s `:head-extra`) per §5.8's
  SHOULD — the way back from page to document that Webmention verification needs,
  even though we don't do Webmention ourselves; costs nothing to emit.
- **Judgment call, not fully resolved by the spec text:** §3's conformance table
  lists `page` under "L2 additions", but §5.8 itself frames it as a general MAY
  ("optional for conformance... the reference client always emits it") independent
  of level. Reading emitting it as evidence of an L2 *claim* seems wrong — we do
  none of L2's actual cross-origin machinery — so: emit `page`, keep `"level": 1`.
  Readers MUST NOT gate on `level` anyway (§3.2), so this is low-stakes either way.
- Explicitly NOT doing, all correctly L2/out-of-scope: Webmention (§15),
  `stub_of` (§10.6), `forked_from` lineage (§5.6), `blogroll.opml` (§11),
  cross-origin transclusion resolution (§10.2 rule 2), `cited` (§5.9). Pins (§8)
  were already out of scope pre-0.3 and remain so.
- Test/verify: existing `blygger_test.clj` assertions on exact JSON shape will need
  updating for the new keys; otherwise no behavior change, so this is safe to ship
  ahead of thread support.

### Part B: same-origin threads

The ask, restated precisely against the spec: a `#blyg` block *with children*
becomes `"kind": "thread"` instead of `"fragment"`, and each child is transcluded
via `![[id]]` (§10.1) rather than (as today) just recursively flattened into one
fragment's nested HTML. A `#blyg` block with no children is completely unchanged —
still a plain fragment, exactly as now.

**Key simplification for v1: this is entirely structural, not authored.** 0.2/0.3
both require every transclusion directive to resolve to an already-published local
item, and both explicitly reserve the general "author freely writes `![[id]]`
anywhere, referencing any already-published item's id" case for later (0.2: thread
nesting itself is L2/future; 0.3: nesting is lifted but cross-origin is the point,
not free-form same-origin composition). What's actually being asked for — "block
tagged #blyg with subblocks" — doesn't need the grammar at all: the transclusion
targets are exactly the tagged block's own children, known structurally at publish
time. So v1 needs **no `resources/parser.ebnf` changes, no id-lookup/resolution
step, no cycle-closure check** (a child can't be an ancestor of its own parent in a
Logseq block tree, so the cycle case §10.2 rule 3 exists to catch is structurally
unreachable here). Free-form author-written `![[id]]`/`[[id]]` directives — "I want
to quote this other already-published fragment from over there" — is a real,
separate, larger feature (needs grammar support, an id-resolution step against the
state file, and the closure check for real) and should be its own later stage, not
folded into this one.

**Design:**

- `blyg-blocks` (candidate discovery) is unchanged for finding `#blyg`-tagged
  blocks. New: for each candidate, check `(:children block)` (after the same
  privacy filter `body-children` already applies) — empty → today's fragment path,
  unchanged. Non-empty → thread path, below.
- **v1 scope is flat, immediate children only** — matches "4 points", one level.
  A child that itself has children keeps today's behavior (its own children fold
  into *its* content, same as any ordinary fragment) rather than recursing into
  nested threads — deeper nesting is a real 0.3 L2 concept (thread-in-thread) and
  explicitly deferred.
- **Each child becomes its own ordinary fragment**, published through the exact
  same `next-entry`/state-file machinery a top-level `#blyg` block uses today —
  the only difference is it's discovered as a thread's child rather than found
  directly by the `#blyg` tag scan. It gets its own `:blyg-id`, its own version
  history, its own `f/{id}/` permalink page. This reuses everything; no new state
  shape.
- **Thread's `content_md`** = the parent block's own text (tag stripped, as today)
  + one `![[child-id]]` line per child, in child order. (If the parent block has no
  text of its own beyond the tag — just a bare `#blyg` header over a list of
  points — that's fine, the directives alone are a valid thread body.)
- **Thread's `content_html`** = parent's own rendered content + one baked
  `<blockquote class="blyg-transclusion" data-blyg-id="{child-id}"
  data-blyg-version="{n}">{child's rendered content_html}</blockquote>` per child,
  in order — own-origin, so no `data-blyg-origin` attribute (§10.2).
- **`transclusions` array** on the thread's item JSON: `[{:id child-id
  :version child-version}, ...]` in child order, `origin` omitted (own-origin,
  §10.3). Fragments keep omitting the key entirely, as now.
- **Versioning falls out for free.** Since `publish!` already recomputes
  `content-md`/`content-html` fresh every run from current `bm` + state, and only
  bumps version on an actual hash change, baking *current* child content on every
  thread publish and only version-bumping on real diffs is exactly the "republish
  re-resolves every directive to the current snapshot" rule (§10.2) — no separate
  invalidation logic needed. Between publishes the thread's last-baked snapshot is
  untouched regardless of what children do (§10.4), which also falls out for free
  since we simply don't run `publish!` continuously.
- **Privacy stays per-block.** A child with its own explicit exit tag
  (`bd/privacy-exit-point?`) is excluded from the thread individually — skip its
  directive/blockquote, log a warning — rather than failing the whole thread.
  Same philosophy `blyg-blocks` already uses for top-level candidates.
- **Withdrawal.** An orphaned child (its parent thread untagged, or the child
  itself removed/moved out from under it) should flow through the existing
  `:withdraw?` opt-in path like any other candidate whose tag disappeared — no new
  withdrawal mechanism, just make sure child items are included in the
  current-vs-prior id diffing in `publish!`, not just top-level `#blyg` finds.
- **The "link to the #blyg block" TODO** — for both the thread and each
  transcluded child, extend the existing "From [Page Title](url)" attribution
  (`source-page`/`item-full-content-md`) to append the block's own stable anchor:
  `#<block-id>` (the site already uses the block's uuid as its `:id` and as HTML
  anchor ids — see `design/done/stable-block-ids.md`), so the link lands on the
  exact block instead of the top of the page.

**Open questions to settle while implementing, not blocking the plan:**
- Exact `kind`/kind-detection wiring: `next-entry` currently takes `kind` as a
  fixed `:fragment` argument from `publish!`'s reduce; needs to vary per-block now.
- Whether `blygger_test.clj`'s hand-built fixture style (fake blocks, no live
  Logseq) is enough to cover a parent-with-children case — should be, it's the
  same pattern already used for `source-page-attribution-test`'s block trees.
- Confirm real ammdi content actually has a `#blyg` parent+children case to
  smoke-test against before calling this done, same verification approach as the
  HTML-surface work (build from the `nbb-extract.edn` snapshot into a scratch dir,
  never touching the live state file/output repo until reviewed).

# Stage 2

TODO small bug, AskClaude lozenges don't appear to work in blyg item


OK, the whole point of this is to publish updates. So really whenever a public page changes, it should generate an automatic Blyg item.

Maybe this involves threads? Don't understand them well.

Thought: , I haven't touched threads yet. Hm, I realize I could take my whole graph, turn each public block into a blygger fragment...but that would be a lot. Current stats: {:blocks {:total 122511, :published 27739}, :pages {:total 6507, :published 1522}}. Not sure the scaling issues have been thought out enough yet? But I could blyg-ify selected pages as threads.

A first cut: turn a page into a thread of fragments. Doesn't have to be all of them, could be controlled with a tag. Hm OK I guess that works in the current scheme, just with 

## Claude think about it
	
