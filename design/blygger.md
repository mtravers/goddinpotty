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

### Implemented (both parts)

Part A shipped as #11. Part B shipped on `feature/blyg-threads`. Before writing
any Part B code, checked the real ammdi `.blyg-state.edn` against the real bm
(built from the `nbb-extract.edn` replay, same approach as the HTML-surface
verification): **2 of 11** currently-published items have children and would
convert fragment→thread — `13y1x5g2hfghfty85f10sg8gjv` (10 children) and
`1563bb7ahney0aqqegzz122bbf`, the "Blygger page" block this whole feature was
asked for (8 children, 1 privacy-excluded → 7). Reported that to the user
before proceeding (a live feed with subscribers; not a call to make silently).

Implementation notes, mostly matching the plan above:

- `publish!` is now two-phase: Phase 1 publishes every fragment (leaf tagged
  blocks + children promoted out of thread blocks) through the existing
  `next-entry` machinery, unchanged; Phase 2 publishes threads, using Phase
  1's resulting `items` map to resolve each child's current blyg-id/version
  for `![[id]]` directives and `transclusions`. `current-ids` (withdrawal
  diffing) is built from the exact same `tagged`/`thread-blocks`/
  `promoted-children` values the two phases publish from, not a separate
  derivation, per the advisor's warning about that being where silent
  withdrawal bugs hide.
- `next-entry` gained a `transclusions` param but needed no other change --
  hashing is still content-md-only (per spec), and a thread's content-md only
  embeds child *ids* (directives don't carry versions, §10.1), so a child's
  content changing alone doesn't change its parent thread's hash. The
  existing "return `existing` unchanged if hash matches" branch already
  discards the freshly-computed (but identical-by-id-set) content-html in
  that case. That *is* §10.4's snapshot-independence guarantee -- not
  separately implemented, just a consequence of hashing the right thing.
- `own-content-md`/`own-content-hiccup` factored out of `item-content-md`/
  `item-content-hiccup` (which still recurse into children for an ordinary
  fragment/promoted child) and reused for a thread's own text, which does
  *not* recurse -- children are transcluded, not flattened.
- **Real finding, not anticipated in the plan:** tested a "child has its own
  explicit exit tag" case and initially got it wrong. `bd/tagged?`'s
  "contained" convention (a `#Private` block nested one level under the
  content it privatizes -- `batadase.clj`'s `tagged?`) checks a block's
  *direct children's* refs too, not just its own. Putting `#Private` directly
  in a child's own text makes `tagged-or-contained?` true for the **thread
  parent** as well (the child is the parent's direct child), which gets the
  whole thread excluded by `blyg-blocks`'s top-level filter before any
  per-child logic runs -- not the "drop just that one point" behavior the
  plan assumed. The correct way to privatize one point in a thread is the
  normal site-wide nested convention: `#Private` on a child *of* the point
  being privatized (two levels under the thread parent), which excludes only
  that one child via `body-children`'s per-child filter without reaching the
  parent. `thread-privacy-test` in `blygger_test.clj` covers this, and the
  real ammdi Blygger-page thread turned out to already have exactly this
  case live (one child legitimately excluded on the real run) -- good,
  unplanned confirmation against real content.
- `reseal!` is now kind-aware: a thread re-renders via `thread-content-html`
  against its children's *existing* (unchanged) state entries, rather than
  the plain `item-content-html` path, which would have silently flattened
  children into the thread's body again instead of transcluding them.
- `title-excerpt` (permalink `<title>`/`<h1>`) strips `![[id]]` directive
  lines in addition to the existing "From [Page]" attribution strip, or a
  thread with little own text gets a title full of raw directives.
- Verified against real ammdi content (scratch state-file copy + scratch
  output dir, same isolation as the HTML-surface work): both real threads
  produced the exact predicted transclusion counts, `content_md`/
  `transclusions` match the wire shape exactly, generated `<blockquote
  class="blyg-transclusion" data-blyg-id=... data-blyg-version=...>`
  elements are correct, and the absolute-URL-only invariant (the thing that
  broke production once already in this feature's history, see above) holds
  across all 29 generated pages.

The "link to the #blyg block with `#id`" TODO is also done: `abs-page-url`/
`md-page-link` take an optional anchor, and `with-attribution-md`/
`-hiccup` pass the block's own `:id` (the stable uuid already used as its
HTML anchor id, `design/done/stable-block-ids.md`), so every "From [Page
Title]" attribution link -- thread or fragment, parent or promoted child --
lands on the exact block instead of the top of the page.

**Not done, deliberately out of scope for this stage** (see "Key
simplification for v1" above): author-written `![[id]]`/`[[id]]` directives
referencing arbitrary already-published items; nested/grandchild threads.

# Stage 1.6

I'm told threads need to be pinned to be forkable by other people.

## Claude thoughts

Read protocol-v0.3.md §8 (Pins) and §5.6 (`forked_from`) against what's
implemented. Short version: `forked_from` (L2, someone else's client) can
only point at a **pinned** version of your item (§5.6 rule 2 — "the
referenced version MUST be pinned... a pin is the only version anyone can
promise a lineage still points at"), because by default we only ever serve
the *latest* version of anything (§5.2, "the publisher's history stays
private by default"). Pinning itself is an **L1** publish-side feature
(explicitly listed under L1 in §3's conformance table, alongside threads) —
we don't need any cross-origin machinery to do our half; we just need to
make a specific version durably fetchable so an L2 reader elsewhere can cite
it. Currently there is no trace of this beyond one dead field: `item-json`
already has a no-op `(:pinned c) (assoc :pinned true)` in the changelog
mapping, but nothing ever sets `:pinned`, and no version's content is
retained anywhere past being superseded — `next-entry` overwrites
`content-md`/`content-html` in place on every bump.

**Real architectural constraint, not a choice: v1 can only pin the
*currently-latest* version, at the moment you ask.** The state file has
never retained old content (`:changelog` is metadata only — version/at/note
— never content), and the protocol's whole design intent is that unpinned
history stays genuinely withheld, not silently kept around "just in case."
So "pin retroactively" (§8 rule 2, explicitly allowed) means *retroactively
relative to when you decide to care*, not *resurrect content that already
got overwritten*. Good news: this matches the actual use case fine — "I'm
told to pin this so it's forkable" is naturally "pin it now, as it currently
stands."

**Design:**

- **New state shape**: each item entry gains a `:pins` map, `version-number
  -> {:content-md :content-html :content-hash :at}` — captured once, at the
  moment a version is pinned, from exactly the content that version was
  published with. Entirely additive; doesn't touch `:changelog`'s existing
  shape except setting `:pinned true` on the relevant entry (the dead field
  `item-json` is already wired for).
- **Low-level primitive**: `pin!` (new fn, `blygger.clj`) takes a state atom
  (or path) + block-id, pins that item's *current* latest version if not
  already pinned (no-op if it already is — idempotent, matches "irrevocable"
  rather than "re-pinnable"). This is the thing everything else calls.
- **Authoring trigger — open question, proposing a default:** mirror how
  `#blyg` itself works rather than inventing a REPL-only workflow: a
  `#blyg/pin` tag (or `#pin`, bikeshed-able) on the same block. At publish
  time, if present and the block's current latest version isn't pinned yet,
  pin it. **Consequence worth being explicit about**: if the author keeps
  editing *after* adding the tag and leaves it on, every subsequent version
  published while it's present gets pinned too (each check is just "is the
  *current* latest pinned yet" — there's no concept of "pin once then
  ignore the tag"). That reads as reasonable default behavior ("pin this
  thread's evolution from here on") rather than a footgun, but flagging it
  since it's a real behavioral choice, not an accident. `pin!` itself (the
  primitive above) is the fallback for "pin this one exact thing right now"
  without relying on tag state, callable from the REPL same as
  `blyg-publish!`/`refresh-blyg!` already are.
- **Writing the surface**: `write-surfaces!` gets a new step, writing
  `items/{blyg-id}/v{n}.json` for every entry in every item's `:pins` map,
  every build (same discipline as `items/{id}.json` — these must never stop
  being regenerated, since `:output-dir` is wiped and rebuilt wholesale each
  time; `.blyg-state.edn` is the only durable copy, same as everything
  else). New `pin-json` builder, not `item-json` reused: per §8's example
  shape, a pinned document is flatter than a live item doc — `blyg`, `id`,
  `kind`, `version`, `at`, `note`, `pinned: true`, `origin`, `author`,
  `content_md`, `content_html`, `content_hash` — no `page`, no `changelog`,
  and explicitly **no `media` array** (§8 rule 4).
- **Threads pin the same way** (§8 rule 5) — a pinned thread version serves
  its already-baked `content_html` (transclusion blockquotes included) and
  its `transclusions` array as they stood at that version; nothing extra
  needed beyond what `:pins` already captures, since we bake threads fully
  at publish time regardless.
- **Not doing in v1**: the optional human-readable `{page}v{n}/` page variant
  (§8.4 — explicitly MAY, not required; the JSON promise is the whole
  conformance requirement). Media retention (§8 rule 4, "media referenced by
  any pinned version MUST be retained forever") is a real soft spot worth
  naming honestly rather than overclaiming: images ride inline as already-
  absolutized `<img src="https://ammdi.hyperphor.com/...">` URLs, so a
  pinned version stays correct as long as the *main site* never deletes or
  renames that image later — goddinpotty has no pin-aware image-retention
  logic tied to this, same gap `media: []` already notes elsewhere in this
  doc. `forked_from`/lineage itself (receiving/verifying a fork) is L2 and
  explicitly not our job to implement — we only need our half: making the
  cited version promise-keepable.

**Resolved and implemented, revised once after user feedback.** First pass
used `#[[blyg/pin]]` (double-bracket form), required because the hashtag
grammar's bare form is `#"\#[\w-:]+"` — no `/` — so bare `#blyg/pin` parses
as the plain `#blyg` tag followed by literal, un-stripped `/pin` text, not a
distinct tag. Reported as "that bites" — fair; double-bracket syntax for a
meta/control tag is exactly the kind of friction that stops a tagging
convention from being used. **Changed to `#blyg-pin`**: `-` *is* in the
bare-form character class, so it's one clean token with plain `#` syntax,
same as `#blyg` itself. **Also changed, per the same feedback: `#blyg-pin`
now implies `#<tag>`** — a block carrying only `#blyg-pin` (no separate
`#blyg`) still publishes *and* pins, one tag doing both instead of two.

Implementation: `pin-tag` (`"<tag>-pin"`), `pin-requested?` (detection,
reusing `tag-block?`), `blyg-tagged?` (`(or (tag-block? tag block)
(pin-requested? block))` — the "implies" logic; `blyg-blocks` uses this
instead of a bare `tag-block?` check now), `pin-entry` (the idempotent,
irrevocable state mutation — snapshots `:content-md`/`:content-html`/
`:content-hash`/`:transclusions` at the version's *current* content into a
new `:pins` map on the entry, marks the matching changelog entry `:pinned
true`; refuses to pin a withdrawn entry per §8 rule 2), `pin-json` (the
flatter, media-less per-version wire shape §8 specifies), and a
`write-surfaces!` step writing `items/{id}/v{n}.json` for every entry in
every item's `:pins`, every build (same "regenerate from durable state on
every run" discipline as everything else here, since `:output-dir` is wiped
wholesale each time). `parsed->blyg-md` and `strip-tag-parsed` both strip
`#blyg-pin` the same way they already stripped `#blyg`, so it doesn't leak
into content. `publish!` runs pinning as a new Phase 3, after threads
(Phase 2) so a pinned thread's snapshot includes its already-resolved
`:transclusions`, before withdrawal.

"Stays pinning while the tag's on" semantics unchanged from the original
proposal: if you leave `#blyg-pin` on and keep editing, every subsequent
version published while it's present gets pinned too, not just the first.

Verified against real ammdi content (scratch state-file + output dir, never
touching the live ones): a full publish run with the new code produced
identical visible-item counts to before (no real content has the tag yet,
so zero pins created, zero regressions) — fixture tests in `pin-test`
exercise the actual pin/implies-main-tag/idempotent-republish/thread-pin
paths end to end.

# Stage 2

TODO small bug, AskClaude lozenges don't appear to work in blyg item


OK, the whole point of this is to publish updates. So really whenever a public page changes, it should generate an automatic Blyg item.

Maybe this involves threads? Don't understand them well.

Thought: , I haven't touched threads yet. Hm, I realize I could take my whole graph, turn each public block into a blygger fragment...but that would be a lot. Current stats: {:blocks {:total 122511, :published 27739}, :pages {:total 6507, :published 1522}}. Not sure the scaling issues have been thought out enough yet? But I could blyg-ify selected pages as threads.

A first cut: turn a page into a thread of fragments. Doesn't have to be all of them, could be controlled with a tag. Hm OK I guess that works in the current scheme, just with 

## Claude think about it
	
