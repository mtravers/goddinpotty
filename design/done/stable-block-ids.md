# Plan: stable block IDs for the Logseq pipeline

(design/TODOs.org: "The ids for blocks within a page are not stable, making links to
them broken". See also the existing TODO at rendering.clj:431.)

**Root cause.** In `import/logseq.clj:127` (`logseq-nbb->blocks-base`), every block's
`:id` — the key used for basically everything (block-map keys, parent/child links, page
association, HTML anchor ids like `<ul id="...">`, TOC/incoming-link hrefs) — is set from
`(:db/id block)`, Datascript's internal entity id. That id is assigned per Datascript
session/reindex and isn't meant to persist; it's why `rendering.clj:431` already has a
TODO calling this out. Meanwhile every block *also* carries `(:block/uuid block)` —
Logseq's actual persistent identity, already used correctly for `((block-ref))`
resolution via `uid-indexed` in `rendering.clj`. The fix is to key everything off that
uuid instead of `:db/id`.

**Where to make the change.** Contained entirely inside `logseq-nbb->blocks-base`
(import/logseq.clj), which is the one place raw nbb pull-maps get turned into the
normalized block records the rest of the codebase consumes. Nothing downstream
(`batadase.clj`, `rendering.clj`, `templating.clj`, `html_generation.clj`, `graph.clj`,
`index.clj`, search, exports) treats `:id` as anything but an opaque key — confirmed by
grepping for numeric/string-type branching on it (none found) — so they need no changes.

**Mechanics:**
1. Build `id->uuid`, a map from every extracted block's `:db/id` → `(str :block/uuid)`,
   from the *full* raw pull list before any filtering.
2. In the same pass that builds each block record, replace:
   - `:id` ← the block's own uuid (already computed as `:uid`)
   - `:parent` ← `(id->uuid (get-in block [:block/parent :db/id]))`
   - `:left` ← `(id->uuid (get-in block [:block/left :db/id]))`
   - `:page` ← `(id->uuid (get-in block [:block/page :db/id]))`
3. Leave `add-children`/`order-children`/`u/index-by :id` untouched — they just need
   `:id`/`:parent`/`:left` to agree, which they will since all four are derived from the
   same `id->uuid` table.

**Specific gotcha guarded against:** `order-children` seeds its walk with
`left = (:id parent)` and looks for the child whose `:left` equals that. This only works
if a child's remapped `:left` and its parent's remapped `:id` are *literally* the same
value — true by construction here, but worth an explicit sanity check (assert every
non-nil `:left` matches some other block's `:id` after remapping) so a mismatch shows up
as a loud error rather than the existing "nils in `:children`" failure mode.

**Scope check — what's unaffected:**
- Refs (`[[links]]`/`#tags`) are resolved by *name*, not by raw Datascript ref-datoms
  (`database.clj/resolve-page-name`), so they're untouched.
- The Roam-legacy import path (`database.clj/get-block-id`) already uses Roam's own
  stable `:uid`, not a Datascript id — not part of this bug.

**One-time side effects to expect after the switch (not bugs, just churn):**
- `import/roam_images.clj:50` embeds `(:id block)` in published image filenames — those
  filenames change once.
- `.enduro.d/` disk caches keyed on old numeric ids go stale — one slow rebuild, not a
  correctness issue.
- Every existing `#<id>` anchor/permalink in already-published HTML changes one more
  time on the next build (unavoidable, one-time), then should stay stable going forward.

**Honest caveat on what this actually fixes.** Confirmed empirically (running
`nbb-extract` twice on the `logseq-test` graph) that `:block/uuid` is stable across
repeated extraction of the same underlying Logseq snapshot, and the codebase already
relies on uuid stability for `((block-ref))` resolution across the live graph's history
without reported breakage — decent circumstantial evidence uuids survive real
reindexing, not just repeated reads. Could not directly test stability across an actual
Logseq resave/reindex from this environment (that needs the live macOS Logseq GUI, per
`bin/update.sh`). Recommend a real-world check before considering this fully closed: note
a few `#<id>` URLs on the live ammdi site, run `bin/generate.sh` for real (forces a
Logseq save), and confirm those specific blocks keep the same id. If some blocks' uuids
turn out to get regenerated (Logseq is documented to sometimes lazily assign a uuid only
when first referenced), a fallback synthetic id (page name + heading path/position, per
the existing rendering.clj:431 TODO) would be needed as a second layer — not building
that speculatively unless the simpler fix proves insufficient.

**Verification plan:**
1. `lein test goddinpotty.import.logseq-test` and `goddinpotty.core-test` (the real
   `generate-from-logseq` build) — must still pass.
2. Full `lein test` — same pre-existing `wayback-test` failure expected (network,
   unrelated), nothing else.
3. Spot-check the block-map: confirm `:id` values are now uuid strings, no
   `nil`/malformed `:children`, and the left/id-agreement assertion above holds.
4. Generate the test-graph site and grep output HTML to confirm `<ul id="...">` now
   shows uuids, and internal TOC/incoming-link `#`-anchors still resolve.
5. If possible, do a real `bin/generate.sh` run against ammdi before/after and diff the
   id sets for a few blocks that survive unchanged in content — the strongest real-world
   confirmation of the caveat above.
