(ns goddinpotty.export.blygger
  "Publish side of the Blygger protocol (protocol-v0.2). Blocks tagged
  with the configured #blyg tag become versioned 'fragment' items
  published as a static blyg surface (manifest, feed, archive index,
  item documents) under the configured mount.

  See design/blygger.md for the design writeup: scope (fragments only, no
  threads/pins/blogroll/generation-provenance in this first cut), the
  persistent state-file format, and the version-bump policy.

  #blyg is its own publication decision, independent of whether the site's
  entry/exit-tag graph walk would otherwise generate a page for the block: a
  #blyg tag publishes a block even if it's on an orphaned page nothing links
  to, or on a journal/daily-notes page the site wouldn't otherwise display.
  The only thing that still gates it is an explicit exit tag
  (#Private/#ExitPoint/etc, via bd/privacy-exit-point?) somewhere in the
  block's own tags or its page-hierarchy ancestors -- that's the actual
  privacy boundary in this codebase (see batadase.clj), and #blyg does not
  override it."
  (:require [goddinpotty.batadase :as bd]
            [goddinpotty.config :as config]
            [goddinpotty.rendering :as r]
            [goddinpotty.templating :as templating]
            [goddinpotty.utils :as utils]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pprint]
            [hiccup2.core :as hiccup2]
            [me.raynes.fs :as fs]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.cljcore :as ju]
            ))

;;;; ⩇⩆⩇ Ids, hashing, timestamps ⩇⩆⩇

;;; Crockford base32, lowercase, no i/l/o/u -- matches blygger-spec/worker/src/util.ts
(def ^String id-alphabet "0123456789abcdefghjkmnpqrstvwxyz")

(defn- encode-base32
  [^bytes bs n-chars]
  (let [n (java.math.BigInteger. 1 bs)]
    (loop [n n, i 0, acc ()]
      (if (= i n-chars)
        (apply str acc)
        (recur (.shiftRight n 5)
               (inc i)
               (cons (.charAt id-alphabet (.intValue (.and n (java.math.BigInteger/valueOf 31)))) acc))))))

(defn new-id
  "128 random bits as 26 chars of lowercase Crockford base32. Permanent item identity."
  []
  (let [bs (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (encode-base32 bs 26)))

(defn content-hash
  "\"sha256:\" + hex(SHA-256(content-md as UTF-8)). Covers content_md only."
  [content-md]
  (let [^String s (or content-md "")
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest)))))

(defn now-iso
  "ISO 8601 UTC, second precision, Z suffix."
  []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- rfc822
  [iso]
  (.format (java.time.format.DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'" java.util.Locale/US)
           (.atZone (java.time.Instant/parse iso) java.time.ZoneOffset/UTC)))

;;;; ⩇⩆⩇ Finding candidate blocks ⩇⩆⩇

;;; Tag must be inline in the block's own content (like hover-tags), not the
;;; "tag in a contained child" convention #Private etc use -- block-hashtags
;;; only looks at this block's own parse tree, which is what we want: the
;;; tagged block itself, plus its (non-excluded) children as the item's body.
(defn- tag-block?
  [tag block]
  (contains? (set (bd/block-hashtags block)) tag))

;;; A block carrying this gets its *current* latest version pinned at
;;; publish time -- see design/blygger.md Stage 1.6 / §8. Hyphenated, not
;;; #<tag>/pin: the hashtag grammar's bare form is #"\#[\w-:]+" -- "-" is
;;; in that class, "/" isn't, so #blyg-pin is one clean token with plain #
;;; syntax, where #blyg/pin would silently split into #blyg plus literal,
;;; un-stripped "/pin" text. Implies #<tag> -- blyg-tagged? below treats a
;;; #blyg-pin-only block (no separate #blyg needed) as a full candidate --
;;; so #blyg-pin is both "publish this" and "pin it", in one tag.
(defn- pin-tag
  []
  (str (or (config/config :blygger :tag) "blyg") "-pin"))

(defn- pin-requested?
  [block]
  (tag-block? (pin-tag) block))

(defn- blyg-tagged?
  [tag block]
  (or (tag-block? tag block) (pin-requested? block)))

;;; Deliberately NOT bd/included?/bd/displayed?/bd/exit-point? -- those track
;;; whether the site's entry-tag graph walk would generate this block a page
;;; (exit-point? also folds in :excluded?, database.clj's journal/daily-notes
;;; performance-skip flag, which is unrelated to privacy), which is a different
;;; question from "is this explicitly marked private". #blyg is its own entry
;;; point -- including on journal pages -- and only a real exit tag gates it.
(defn- excluded?
  [bm block]
  (bd/privacy-exit-point? bm block))

(defn blyg-blocks
  "Blocks tagged #<tag> (or #<tag>-pin, which implies #<tag>), published
  regardless of whether the site's entry-tag graph walk would otherwise
  reach them -- only an explicit exit tag (#Private/#ExitPoint/etc) excludes
  one. Logs (and drops) any such block an exit tag excludes, rather than
  silently publishing -- or silently ignoring the author -- either way."
  [bm tag]
  (keep (fn [block]
          (cond (not (blyg-tagged? tag block))
                nil
                (excluded? bm block)
                (do (log/warn "blyg: skipping" (:id block) "- excluded:"
                               (bd/privacy-exit-point-why bm block))
                    nil)
                :else block))
        (vals bm)))

;;; Also the source of a #blyg thread's children (Stage 1.5 / design/blygger.md)
;;; -- a tagged block with any (surviving) children becomes a thread whose
;;; children get promoted to their own fragments, rather than flattened into
;;; one blob the way a plain fragment's descendants are.
(defn- body-children
  [bm block]
  (->> (:children block)
       (map bm)
       (remove (fn [child]
                 (when (excluded? bm child)
                   (log/warn "blyg: excluding child block" (:id child) "- excluded:"
                             (bd/privacy-exit-point-why bm child))
                   true)))))

;;; A #blyg block's "source page" is worth attributing when it's a normal
;;; content page (not a journal/daily-notes entry, which has no meaningful
;;; page identity of its own) that the site actually publishes -- linking to
;;; an unpublished or nonexistent page would be a dead/private link.
(defn- source-page
  [bm block]
  (let [page (bd/block-page bm block)]
    (when (and page (not (bd/daily-notes-page? page)) (bd/displayed? bm page))
      page)))

;;;; ⩇⩆⩇ Content: markdown ⩇⩆⩇

;;; anchor, when given, is a block's own stable uuid -- the site already uses
;;; it as the block's :id and as HTML anchor ids (design/done/stable-block-ids.md)
;;; -- so attribution links can land on the exact #blyg block, not just the
;;; top of its page.
(defn- abs-page-url
  [title & [anchor]]
  (str (config/config :real-base-url) (utils/clean-page-title title)
       (when anchor (str "#" anchor))))

(defn- md-page-link
  [title & [link-text anchor]]
  (format "[%s](%s)" (or link-text title) (abs-page-url title anchor)))

;;; Cut down, absolutizing sibling of export.markdown/parsed->markdown: strips
;;; the blyg tag itself and points internal links at the live site rather than
;;; at sibling .md files.
(defn- parsed->blyg-md
  [tag parsed]
  (letfn [(walk-node [p]
            (if (vector? p)
              (case (first p)
                :block (str/join "" (map walk-node (rest p)))
                :blockquote (str "> " (walk-node (second p)))
                :page-link (md-page-link (utils/remove-double-delimiters (second p)))
                :hashtag (let [name (utils/parse-hashtag (second p))]
                           (if (#{tag (pin-tag)} name) "" (md-page-link name (str "#" name))))
                :alias (let [[_ text target] (r/parse-alias (second p))]
                         (if (str/starts-with? target "[[")
                           (md-page-link (utils/remove-double-delimiters target) text)
                           (second p)))
                ;; (second p) alone would drop every child after the first --
                ;; harmless while italic/bold almost always had exactly one
                ;; (a single merged string), but reachable now that an escaped
                ;; char (eg *W\** -> [:italic "W" "*"]) can split it into more.
                (:italic :bold) (str/join "" (map walk-node (rest p)))
                (:image :code-block :code-line :hr) (second p)
                :block-property ""
                :bare-url (second p)
                :todo "◘"
                :done "⌧"
                :block-ref (second p)
                :youtube (second p)
                (do (log/warn "blyg: don't know how to render to markdown" p) ""))
              (str p)))]
    (str/trim (walk-node parsed))))

(defn- own-content-md
  [tag block]
  (parsed->blyg-md tag (:parsed block)))

(defn- item-content-md
  [bm tag block]
  (let [own (own-content-md tag block)
        kids (body-children bm block)]
    (str/join "\n\n"
              (remove str/blank? (cons own (map (partial item-content-md bm tag) kids))))))

;;; Attribution line prepended once, at the top level -- shared by both plain
;;; fragments and threads (not folded into the recursive item-content-md, or
;;; every child would repeat it).
(defn- with-attribution-md
  [bm block body]
  (if-let [page (source-page bm block)]
    (str "From " (md-page-link (:title page) nil (:id block)) "\n\n" body)
    body))

;;; attribution? false for a promoted child (see publish!): the thread it
;;; belongs to already carries one "From [Page]" line for the whole piece --
;;; repeating it inside every transcluded child read as noise, not
;;; provenance. A top-level fragment (never a child, attribution? defaults
;;; true) still gets its own.
(defn- item-full-content-md
  [bm tag block & {:keys [attribution?] :or {attribution? true}}]
  (if attribution?
    (with-attribution-md bm block (item-content-md bm tag block))
    (item-content-md bm tag block)))

;;; A child's own blyg-id, already resolved in `items` (built in an earlier
;;; publish! pass -- see there) -- never the child's content itself, which
;;; the directive doesn't carry (§10.1: no version in the grammar either).
(defn- thread-transclusion-md
  [items child]
  (format "![[%s]]" (:blyg-id (get items (:id child)))))

;;; Parent's own text + one directive per (already-promoted) child, in
;;; order -- not recursive the way item-content-md is: children are
;;; transcluded, not flattened into this block's own prose.
(defn- thread-content-md
  [tag items block kids]
  (str/join "\n\n"
            (remove str/blank? (cons (own-content-md tag block)
                                      (map (partial thread-transclusion-md items) kids)))))

(defn- thread-full-content-md
  [bm tag items block kids]
  (with-attribution-md bm block (thread-content-md tag items block kids)))

;;;; ⩇⩆⩇ Content: html ⩇⩆⩇

;;; Reuse the site's real renderer (images, aliases, embeds, headings, ...)
;;; for fidelity; only surgery is blanking out the tag node itself.
(defn- strip-tag-parsed
  [tag parsed]
  (walk/postwalk (fn [node]
                   (if (and (vector? node) (= :hashtag (first node))
                            (#{tag (pin-tag)} (utils/parse-hashtag (second node))))
                     ""
                     node))
                 parsed))

(defn- own-content-hiccup
  [bm tag block]
  (let [stripped (assoc block :parsed (strip-tag-parsed tag (:parsed block)))]
    [:p (r/block-hiccup stripped bm)]))

(defn- item-content-hiccup
  [bm tag block]
  (let [own (own-content-hiccup bm tag block)
        kids (body-children bm block)]
    (if (seq kids)
      (into [:div own] (map (partial item-content-hiccup bm tag) kids))
      own)))

(defn- absolute-url?
  [url]
  (or (re-matches #"(?i)[a-z][a-z0-9+.-]*:.*" url)
      (str/starts-with? url "//")
      (str/starts-with? url "#")))

(defn absolutize-html
  "Rewrite relative src/href attributes against `base` (real-base-url) -- feed
  <description> and item content_html must not depend on where they're read
  from."
  [html base]
  (let [base (if (str/ends-with? base "/") base (str base "/"))]
    (str/replace html #"(src|href)=\"([^\"]*)\""
                 (fn [[whole attr url]]
                   (cond (str/blank? url) whole
                         (absolute-url? url) whole
                         (str/starts-with? url "/") (str attr "=\"" (str/replace base #"/+$" "") url "\"")
                         :else (str attr "=\"" base url "\""))))))

;;; Same attribution line as with-attribution-md, prepended once at the top
;;; level -- shared by plain fragments and threads.
(defn- with-attribution-hiccup
  [bm block body]
  (if-let [page (source-page bm block)]
    [:div [:p.source-page "From " [:a {:href (abs-page-url (:title page) (:id block))} (:title page)]] body]
    body))

(defn- item-full-content-hiccup
  [bm tag block]
  (with-attribution-hiccup bm block (item-content-hiccup bm tag block)))

;;; attribution? false for a promoted child -- see item-full-content-md.
(defn- item-content-html
  [bm tag block & {:keys [attribution?] :or {attribution? true}}]
  (-> (if attribution?
        (item-full-content-hiccup bm tag block)
        (item-content-hiccup bm tag block))
      hiccup2/html
      str
      (absolutize-html (config/config :real-base-url))))

;;; Baked snapshot of a child's current content-html, wrapped per §10.2 --
;;; bare blockquote + data attributes, no link inside (any provenance link
;;; is presentation, not wire). No data-blyg-origin: own-origin only in v1
;;; (see design/blygger.md Stage 1.5), so this is always the 0.2-compatible
;;; form.
(defn- thread-transclusion-hiccup
  [items child]
  (let [entry (get items (:id child))]
    [:blockquote.blyg-transclusion
     {:data-blyg-id (:blyg-id entry) :data-blyg-version (:version entry)}
     (hiccup2/raw (:content-html entry))]))

(defn- thread-content-hiccup
  [bm tag items block kids]
  (into [:div (own-content-hiccup bm tag block)]
        (map (partial thread-transclusion-hiccup items) kids)))

(defn- thread-full-content-hiccup
  [bm tag items block kids]
  (with-attribution-hiccup bm block (thread-content-hiccup bm tag items block kids)))

(defn- thread-content-html
  [bm tag items block kids]
  (-> (thread-full-content-hiccup bm tag items block kids)
      hiccup2/html
      str
      (absolutize-html (config/config :real-base-url))))

(defn- safe-subs
  "(subs s 0 n), pulled back by one if it would split a surrogate pair."
  [^String s n]
  (let [t (subs s 0 n)
        len (count t)]
    (if (and (pos? len) (Character/isHighSurrogate (.charAt t (dec len))))
      (subs t 0 (dec len))
      t)))

(defn- excerpt
  [md n]
  (let [plain (-> (or md "")
                   (str/replace #"\[([^\]]*)\]\([^\)]*\)" "$1")
                   (str/replace #"\s+" " ")
                   str/trim)]
    (if (<= (count plain) n) plain (str (safe-subs plain n) "…"))))

;;;; ⩇⩆⩇ Persistent state ⩇⩆⩇

;;; block-uuid -> {:blyg-id :kind :created :updated :version :content-md
;;;                :content-html :content-hash :changelog}
;;; This is the one irreplaceable artifact: the block<->blyg-id mapping can't
;;; be reconstructed from the published surface. MUST live somewhere durable
;;; and git-tracked, outside output-dir (core/output-bm deletes output-dir's
;;; old contents on every full build).

(defn- state-file
  []
  (or (config/config :blygger :state-file)
      (throw (ex-info "blyg :state-file must be configured (a durable, git-tracked path)" {}))))

(defn load-state
  [path]
  (if (fs/exists? path)
    (ju/read-from-file path)
    {:schema 1 :items {}}))

(defn- sorted-entry
  [entry]
  (into (sorted-map) entry))

(defn save-state!
  [path state]
  (fs/mkdirs (fs/parent (fs/file path)))
  (spit path
        (with-out-str
          (pprint/pprint
           (-> state
               (update :items (fn [items]
                                 (into (sorted-map)
                                       (u/map-values sorted-entry items))))
               sorted-entry)))))

(defn- next-entry
  "New version of one item's state entry, or the same entry unchanged if
  content-hash is unchanged (no publish event -- no version bump). hash is
  of content-md only (per spec, §5.1), so a thread's transclusions -- which
  only embed child *ids*, never versions (directives don't carry versions,
  §10.1) -- freezes correctly: a child's content changing alone doesn't
  change its parent thread's content-md, so this returns `existing`
  untouched and the freshly-computed content-html/transclusions here are
  simply discarded. That's §10.4's snapshot-independence guarantee, not
  something separately implemented -- a thread's baked snapshot only
  changes when its own content-md does (own text, or which/how-ordered its
  children are), exactly when 'republishing' should re-resolve it.
  transclusions is nil for fragments (key omitted on the wire, §10.3)."
  [existing kind content-md content-html transclusions now]
  (let [hash (content-hash content-md)]
    (cond
      (nil? existing)
      {:blyg-id (new-id)
       :kind kind
       :created now
       :updated now
       :version 1
       :content-md content-md
       :content-html content-html
       :content-hash hash
       :transclusions transclusions
       :changelog [{:version 1 :at now :note nil}]}

      (and (= kind (:kind existing)) (= hash (:content-hash existing)))
      existing

      :else
      (let [v (inc (:version existing))]
        (assoc existing
               :kind kind
               :updated now
               :version v
               :content-md content-md
               :content-html content-html
               :content-hash hash
               :transclusions transclusions
               :changelog (conj (:changelog existing) {:version v :at now :note nil}))))))

(defn- withdraw-entry
  [existing now]
  (let [v (inc (:version existing))]
    (assoc existing
           :kind :withdrawn
           :updated now
           :version v
           :content-md ""
           :content-html ""
           :content-hash (content-hash "")
           ;; "a withdrawn thread's endcap carries []" (§10.3) -- fragments
           ;; keep omitting the key (nil stays nil).
           :transclusions (when (= :thread (:kind existing)) [])
           :changelog (conj (:changelog existing) {:version v :at now :note nil}))))

;;; Pins an entry's *current* (latest) version -- idempotent (already-pinned
;;; is a no-op), irrevocable once written (§8 rule 1: a pinned version file
;;; MUST 200 forever, so this never un-pins). Can only ever pin the version
;;; that's current *right now*: older, already-superseded versions were
;;; never retained (see design/blygger.md Stage 1.6) -- "retroactive"
;;; pinning here means "as it stands today", not resurrecting old content.
;;; Snapshots :transclusions too, so a pinned thread version serves its
;;; own baked transcludes-as-of-that-version (§8 rule 5).
(defn- pin-entry
  [entry]
  (cond
    (= :withdrawn (:kind entry))
    (do (log/warn "blyg: not pinning" (:blyg-id entry) "- withdrawn, nothing to cite (§8 rule 2)")
        entry)

    (contains? (:pins entry) (:version entry))
    entry

    :else
    (-> entry
        (assoc-in [:pins (:version entry)]
                  (cond-> {:content-md (:content-md entry)
                           :content-html (:content-html entry)
                           :content-hash (:content-hash entry)
                           :at (:updated entry)}
                    (:transclusions entry) (assoc :transclusions (:transclusions entry))))
        (update :changelog
                (fn [changelog]
                  (mapv (fn [c] (if (= (:version c) (:version entry)) (assoc c :pinned true) c))
                        changelog))))))

;;;; ⩇⩆⩇ Protocol surface builders ⩇⩆⩇

(defn- overall-updated
  [items]
  (or (last (sort (map :updated (vals items)))) (now-iso)))

(defn- item-json
  [origin entry]
  (let [withdrawn? (= :withdrawn (:kind entry))]
    (cond-> {:blyg "0.3"
             :id (:blyg-id entry)
             :kind (name (:kind entry))
             :origin origin
             ;; New in 0.3 (§5.8) -- origin-relative permalink path, emitted for
             ;; withdrawn items too (the endcap's page is 200 forever). Matches our
             ;; existing f/{id}/ convention exactly, so no new path scheme needed.
             :page (str "f/" (:blyg-id entry) "/")
             :created (:created entry)
             :updated (:updated entry)
             :version (:version entry)
             :content_md (if withdrawn? "" (:content-md entry))
             :content_html (if withdrawn? "" (:content-html entry))
             :content_hash (:content-hash entry)
             ;; TODO: populate media[] explicitly (images currently ride along inline
             ;; in content_html, already-absolutized -- see design/blygger.md).
             :media []
             :changelog (mapv (fn [c] (cond-> {:version (:version c) :at (:at c) :note (:note c)}
                                         (:pinned c) (assoc :pinned true)))
                               (:changelog entry))}
      ;; Threads carry transclusions (§10.3); fragments omit the key
      ;; entirely. (:transclusions entry) is truthy for both a live
      ;; thread's populated vector and a withdrawn-former-thread's [].
      (:transclusions entry) (assoc :transclusions (:transclusions entry)))))

;;; A pinned version's document (§8) -- flatter than a live item doc: no
;;; page, no changelog, no media (§8 rule 4 -- pinned media rides inline in
;;; content_html, relying on the main site never deleting it). :pins entries
;;; only exist for versions pin-entry actually captured, so `pin` here is
;;; never nil for a version write-surfaces! is iterating.
(defn- pin-json
  [origin entry version]
  (let [pin (get-in entry [:pins version])
        note (:note (first (filter #(= version (:version %)) (:changelog entry))))]
    (cond-> {:blyg "0.3"
             :id (:blyg-id entry)
             :kind (name (:kind entry))
             :version version
             :at (:at pin)
             :note note
             :pinned true
             :origin origin
             :author {:name (or (config/config :blygger :author-name) (config/config :short-title))
                      :url origin}
             :content_md (:content-md pin)
             :content_html (:content-html pin)
             :content_hash (:content-hash pin)}
      (:transclusions pin) (assoc :transclusions (:transclusions pin)))))

;;; Defaults to "<short-title> Blyg" (eg "AMMDI Blyg") rather than bare
;;; short-title, so the manifest/feed/archive page read as their own named
;;; thing, distinct from the main site.
(defn- blyg-title
  []
  (or (config/config :blygger :title) (str (config/config :short-title) " Blyg")))

(defn- manifest
  [origin items updated]
  {:blyg "0.3"
   ;; L1: same-origin only. The real substance of 0.3 is L2 (cross-origin
   ;; transclusion, stubs, lineage, Webmention) -- none of which this does;
   ;; L1 is explicitly unchanged in substance from 0.2. Emitting :page
   ;; below doesn't change this: §5.8 frames it as a general MAY, and
   ;; readers MUST NOT gate behavior on :level regardless (§3.2).
   :level 1
   :generator "goddinpotty-blyg/0.1"
   :generator_url "https://github.com/mtravers/goddinpotty"
   :site origin
   :title (blyg-title)
   :author {:name (or (config/config :blygger :author-name) (config/config :short-title))
            :url origin}
   :feed "feed.xml"
   :items "items/index.json"
   :updated updated})

(defn- archive-index
  [items updated]
  {:updated updated
   :items (->> (vals items)
               (map (fn [e] {:id (:blyg-id e) :kind (name (:kind e))
                             :created (:created e) :updated (:updated e) :version (:version e)}))
               (sort-by :updated)
               reverse
               vec)})

(defn- feed-events
  "One entry per publish event, newest first, bounded to feed-window. A
  withdrawn item contributes only its withdrawal event (§7). Thread
  children are excluded -- otherwise every point in a thread would also
  show up as its own 'new' feed entry, duplicating what the thread's own
  entry already carries transcluded."
  [items feed-window]
  (->> items
       vals
       (remove :thread-child?)
       (mapcat (fn [entry]
                 (if (= :withdrawn (:kind entry))
                   [{:entry entry :event (last (:changelog entry))}]
                   (map (fn [c] {:entry entry :event c}) (:changelog entry)))))
       (sort-by (comp :at :event))
       reverse
       (take feed-window)))

(defn- escape-xml
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- cdata
  [s]
  (str "<![CDATA[" (str/replace s "]]>" "]]]]><![CDATA[>") "]]>"))

(defn- feed-item-xml
  [origin {:keys [entry event]}]
  (let [withdrawn? (= :withdrawn (:kind entry))
        blyg-id (:blyg-id entry)
        title (if withdrawn? "withdrawn" (excerpt (:content-md entry) 60))
        desc (if withdrawn? "" (cdata (:content-html entry)))]
    (str "    <item>\n"
         "      <guid isPermaLink=\"false\">blyg:" blyg-id ":v" (:version event) "</guid>\n"
         "      <link>" origin "f/" blyg-id "/</link>\n"
         "      <title>" (escape-xml title) "</title>\n"
         "      <description>" desc "</description>\n"
         "      <pubDate>" (rfc822 (:at event)) "</pubDate>\n"
         "      <blyg:id>" blyg-id "</blyg:id>\n"
         "      <blyg:kind>" (name (:kind entry)) "</blyg:kind>\n"
         "      <blyg:version>" (:version event) "</blyg:version>\n"
         "      <blyg:created>" (:created entry) "</blyg:created>\n"
         "      <blyg:item>" origin "items/" blyg-id ".json</blyg:item>\n"
         "    </item>")))

(defn- feed-xml
  [origin title items feed-window]
  (let [events (feed-events items feed-window)
        updated (overall-updated items)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<rss version=\"2.0\" xmlns:blyg=\"https://blygger.org/ns/0.1\">\n"
         "  <channel>\n"
         "    <title>" (escape-xml title) "</title>\n"
         "    <link>" origin "</link>\n"
         "    <description>" (escape-xml title) "</description>\n"
         "    <lastBuildDate>" (rfc822 updated) "</lastBuildDate>\n"
         "    <blyg:level>1</blyg:level>\n"
         "    <blyg:manifest>" origin "blyg.json</blyg:manifest>\n"
         (str/join "\n" (map (partial feed-item-xml origin) events))
         "\n  </channel>\n</rss>\n")))

;;; HTML surface: reuses the site's own page-hiccup (nav/fonts/css/search) so
;;; blyg pages read as part of the same site, not a bolted-on protocol demo.
;;; Fragment-card layout borrows the shape (content, "Created"/"vN" timestamp
;;; line, permalink) of reference blyg clients like blyg.aneeshsathe.com,
;;; without their bespoke masthead art -- just enough for the surface to be a
;;; legible little archive rather than raw JSON.

(defn- human-date
  [iso]
  (.format (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy" java.util.Locale/US)
           (.atZone (java.time.Instant/parse iso) java.time.ZoneOffset/UTC)))

(defn- fragment-card-hiccup
  [origin entry & {:keys [permalink-page?]}]
  (let [withdrawn? (= :withdrawn (:kind entry))
        blyg-id (:blyg-id entry)]
    [:article.card.my-2.fragment {:class (when withdrawn? "withdrawn")}
     [:div.card-body.py-2
      [:div.item-content
       (if withdrawn?
         [:p.text-muted "[withdrawn]"]
         (hiccup2/raw (:content-html entry)))]
      [:p.meta-line.text-muted.small.mb-0
       "Created " (human-date (:created entry))
       (when (> (:version entry) 1)
         (list " · updated " (human-date (:updated entry)) " · v" (:version entry)))
       (when-not permalink-page?
         (list " · " [:a.permalink {:href (str origin "f/" blyg-id "/")} "Permalink"]))]]]))

;;; Excerpt for <title>/<h1> only -- strip the leading "From [Page](url)"
;;; attribution line first, or every attributed item would be titled "From
;;; <page title> <actual start of content>".
(defn- title-excerpt
  [content-md]
  (excerpt (-> content-md
               (str/replace #"(?s)^From \[[^\]]*\]\([^)]*\)\n\n" "")
               ;; A thread's content-md is own-text + "![[id]]" directive
               ;; lines -- strip those too, or a thread with little own text
               ;; gets a title/<h1> full of raw directives.
               (str/replace #"(?s)\n\n!\[\[[0-9a-z]{26}\]\]" ""))
           60))

(defn- permalink-page-hiccup
  [bm origin entry]
  (let [withdrawn? (= :withdrawn (:kind entry))
        title (if withdrawn? "withdrawn" (title-excerpt (:content-md entry)))
        item-url (str origin "items/" (:blyg-id entry) ".json")
        contents
        [:div.blyg
         (fragment-card-hiccup origin entry :permalink-page? true)
         [:p [:a {:href item-url} "JSON"]
          " · " [:a {:href origin} "Blyg"]]]]
    (templating/page-hiccup contents title title bm
                            :widgets []
                            ;; §5.8 SHOULD: the way back from page to document
                            ;; that Webmention verification needs (§15.4) --
                            ;; costs nothing to emit even though we don't do
                            ;; Webmention ourselves.
                            :head-extra [[:link {:rel "alternate" :type "application/json" :href item-url}]])))

(defn- archive-hiccup
  [bm origin items]
  (let [;; Thread children are hidden here -- they're fully published,
        ;; independently fetchable items (own items/{id}.json, own f/{id}/
        ;; page, listed in items/index.json per §6.2), just not part of the
        ;; human-facing chronological archive, where they'd otherwise show
        ;; the same content twice: once standalone, once transcluded in
        ;; their parent thread's card.
        entries (->> (vals items)
                      (remove #(or (= :withdrawn (:kind %)) (:thread-child? %)))
                      (sort-by :updated)
                      reverse)
        title (blyg-title)
        contents
        [:div.blyg
         [:p.blyg-tagline
          [:a {:href "https://blygger.org/"} "Blygger"] " is a new medium for public writing. This blyg is my personal feed. Links to existing AMMDI functionality are exploratory."
          ]
         [:p.blyg-links
          [:a {:href (str origin "feed.xml")} "RSS"] " · "
          [:a {:href (str origin "blyg.json")} "JSON manifest"]]
         (if (seq entries)
           (map (partial fragment-card-hiccup origin) entries)
           [:p "Nothing published yet."])]]
    (templating/page-hiccup contents title title bm :widgets [])))

;;; page-hiccup's asset/nav hrefs (eg "assets/default.css", page-links like
;;; "About") are relative, correct only for normal top-level pages living
;;; directly at output-dir/<title>. Blyg pages nest one or two directories
;;; deeper (blyg/, blyg/f/{id}/), so those same relative hrefs would resolve
;;; to the wrong place -- absolutize against :real-base-url, same fix as
;;; content_html already gets.
(defn- site-page-html
  [hiccup]
  (absolutize-html (str (hiccup2/html hiccup)) (config/config :real-base-url)))

;;;; ⩇⩆⩇ Writing the surface ⩇⩆⩇

(defn- mount-path
  []
  (let [m (or (config/config :blygger :mount) "blyg/")]
    (if (str/ends-with? m "/") m (str m "/"))))

(defn blyg-origin
  []
  (let [base (config/config :real-base-url)]
    (when-not base
      (throw (ex-info ":real-base-url must be configured for blyg" {})))
    (str (if (str/ends-with? base "/") base (str base "/")) (mount-path))))

(defn- ensure-mount-dir!
  "The mount root can collide with an auto-generated page file of the same
  name -- eg the #blyg tag's own backlink page, since content pages are
  written extensionless (html-generation/generate-content-page) at exactly
  output-dir/<clean-page-title>, which for the default :tag/:mount pair is
  output-dir/blyg. The export directory always wins that collision."
  [base-dir]
  (let [dir-path (str/replace base-dir #"/$" "")
        f (fs/file dir-path)]
    (when (and (fs/exists? f) (not (fs/directory? f)))
      (log/warn "blyg: removing conflicting page file at" dir-path "to make room for the blyg mount")
      (fs/delete f))
    (fs/mkdirs dir-path)))

(defn- write-surfaces!
  [bm state output-dir]
  (let [origin (blyg-origin)
        base-dir (str (str/replace output-dir #"/$" "") "/" (mount-path))
        items (:items state)
        updated (overall-updated items)
        title (blyg-title)
        feed-window (or (config/config :blygger :feed-window) 50)]
    (ensure-mount-dir! base-dir)
    (utils/write-json (str base-dir "blyg.json") (manifest origin items updated))
    (utils/write-json (str base-dir "items/index.json") (archive-index items updated))
    (spit (str base-dir "index.html") (site-page-html (archive-hiccup bm origin items)))
    (doseq [entry (vals items)]
      (utils/write-json (str base-dir "items/" (:blyg-id entry) ".json") (item-json origin entry))
      (let [dir (str base-dir "f/" (:blyg-id entry) "/")]
        (fs/mkdirs dir)
        (spit (str dir "index.html") (site-page-html (permalink-page-hiccup bm origin entry))))
      ;; Pinned versions (§8) -- written fresh every build, same as
      ;; items/{id}.json, since :output-dir gets wiped wholesale each time
      ;; and .blyg-state.edn's :pins is the only durable copy.
      (when (seq (:pins entry))
        (let [pin-dir (str base-dir "items/" (:blyg-id entry) "/")]
          (fs/mkdirs pin-dir)
          (doseq [version (keys (:pins entry))]
            (utils/write-json (str pin-dir "v" version ".json") (pin-json origin entry version))))))
    (spit (str base-dir "feed.xml") (feed-xml origin title items feed-window))
    (log/info "blyg: wrote" (count items) "items to" base-dir)))

;;;; ⩇⩆⩇ Top level ⩇⩆⩇

(defn publish!
  "Scan `bm` for #<tag> blocks the site already publishes and (re)write the
  blyg surface into `output-dir`. Safe to call repeatedly -- version bumps
  only on content change. A tagged block with (surviving) children is a
  thread; each child is promoted to its own ordinary fragment and the
  parent transcludes them (see design/blygger.md Stage 1.5). A tagged block
  with no children is a plain fragment, as always. #<tag>-pin (implies
  #<tag>, no need for both) gets its current latest version pinned (§8,
  irrevocable, idempotent -- see design/blygger.md Stage 1.6). Blocks previously
  published but no longer tagged/promoted are left alone (and warned about)
  unless :withdraw? true. :dry-run? true computes and logs the new state
  without writing anything."
  [bm output-dir & {:keys [withdraw? dry-run?]}]
  (when-not (config/config :blygger :enabled?)
    (throw (ex-info "blyg is not enabled (:blyg :enabled? in config)" {})))
  (let [tag (or (config/config :blygger :tag) "blyg")
        path (state-file)
        state (load-state path)
        now (now-iso)
        tagged (blyg-blocks bm tag)
        ;; body-children already applies the privacy filter, so this split
        ;; reflects *surviving* children, not raw tree structure -- a block
        ;; whose only children are all privacy-excluded is correctly a leaf.
        thread-blocks (filter #(seq (body-children bm %)) tagged)
        leaf-blocks (remove #(seq (body-children bm %)) tagged)
        promoted-children (mapcat (partial body-children bm) thread-blocks)
        fragment-candidates (concat leaf-blocks promoted-children)
        ;; Built from the same tagged/thread-blocks/promoted-children values
        ;; the two phases below actually publish from, in one pass -- not a
        ;; separate re-derivation, so this can't disagree with what gets
        ;; published (a mismatch here means a real published item silently
        ;; lands in withdrawn-ids).
        current-ids (set (concat (map :id tagged) (map :id promoted-children)))
        prior-ids (set (keys (:items state)))
        withdrawn-ids (set/difference prior-ids current-ids)
        _ (when (seq withdrawn-ids)
            (if withdraw?
              (log/warn "blyg: withdrawing" (count withdrawn-ids) (str "item(s) no longer tagged #" tag ":") withdrawn-ids)
              (log/warn "blyg:" (count withdrawn-ids) (str "previously-published item(s) are no longer tagged #" tag)
                        "-- left untouched; pass :withdraw? true to withdraw them:" withdrawn-ids)))
        ;; Needed by Phase 1 itself now (attribution?) as well as the
        ;; visibility-flag pass below -- computed once, used both places.
        child-ids (set (map :id promoted-children))
        ;; Phase 1: every fragment (leaves + promoted children) -- order
        ;; doesn't matter, nothing here depends on anything else. A promoted
        ;; child gets no "From [Page]" attribution of its own (attribution?
        ;; false) -- the thread it's transcluded into already carries one
        ;; for the whole piece; repeating it inside every child read as
        ;; noise, not provenance.
        items-1a (reduce (fn [items block]
                          (let [attributed? (not (contains? child-ids (:id block)))]
                            (update items (:id block)
                                    next-entry :fragment
                                    (item-full-content-md bm tag block :attribution? attributed?)
                                    (item-content-html bm tag block :attribution? attributed?)
                                    nil
                                    now)))
                        (:items state)
                        fragment-candidates)
        ;; :thread-child? is presentation-only, not part of the hashed/
        ;; versioned wire content -- a promoted child is still a fully
        ;; conformant, independently fetchable fragment (its own
        ;; items/{id}.json, its own f/{id}/ page, listed in items/index.json
        ;; per §6.2's "every item ever published"), it's just hidden from
        ;; the human-facing archive page and feed.xml so its content doesn't
        ;; appear twice -- once standalone, once nested in its thread's
        ;; transclusion. Recomputed unconditionally every run (never sticky,
        ;; never version-gated), so a block that stops being a child goes
        ;; straight back to visible.
        items-1 (reduce (fn [items block]
                          (update items (:id block) assoc :thread-child? (contains? child-ids (:id block))))
                        items-1a
                        fragment-candidates)
        ;; Phase 2: threads, now that items-1 has every child's current
        ;; blyg-id/version resolved to transclude.
        items-2-raw (reduce (fn [items block]
                          (let [kids (body-children bm block)
                                transclusions (mapv (fn [k]
                                                       (let [e (get items (:id k))]
                                                         {:id (:blyg-id e) :version (:version e)}))
                                                     kids)]
                            (update items (:id block)
                                    next-entry :thread
                                    (thread-full-content-md bm tag items block kids)
                                    (thread-content-html bm tag items block kids)
                                    transclusions
                                    now)))
                        items-1
                        thread-blocks)
        ;; A thread-blocks member must never stay marked :thread-child? --
        ;; the fragment-candidates pass above can't do this (thread entries
        ;; don't exist until the reduce just above creates them), so a block
        ;; that *was* a promoted child in an earlier run and later became a
        ;; #blyg-pin/#blyg thread in its own right kept a stale true flag
        ;; forever, silently vanishing from the archive page despite being
        ;; correctly published and pinned (real bug, found via a real report:
        ;; the Blygger-page "Implementing threads and pinning" thread).
        items-2 (reduce (fn [items block]
                          (update items (:id block) assoc :thread-child? false))
                        items-2-raw
                        thread-blocks)
        ;; Phase 3: pins (§8, design/blygger.md Stage 1.6) -- #<tag>-pin
        ;; on any currently-tagged/promoted block pins that block's *current*
        ;; latest version (thread content included, now that items-2 has it
        ;; resolved). pin-entry is idempotent, so this is safe to run every
        ;; publish! regardless of whether the tag was already seen before.
        items-2a (reduce (fn [items block]
                           (if (pin-requested? block)
                             (update items (:id block) pin-entry)
                             items))
                         items-2
                         (concat leaf-blocks thread-blocks promoted-children))
        items-3 (if withdraw?
                  (reduce (fn [items id]
                            (if (= :withdrawn (:kind (get items id)))
                              items
                              (update items id withdraw-entry now)))
                          items-2a
                          withdrawn-ids)
                  items-2a)
        new-state (assoc state :items items-3)
        changed (filter (fn [[id e]] (not= e (get-in state [:items id]))) items-3)]
    (if dry-run?
      (do (log/info "blyg dry-run:" (count tagged) "tagged block(s)" (str "(" (count thread-blocks) " thread(s), "
                    (count promoted-children) " promoted child/children)," ) (count changed)
                    "item(s) would change (new version, new item, or kind change), no files written")
          new-state)
      ;; Surfaces before state, deliberately: if this crashes partway, an
      ;; unsaved state means the next run recomputes these same items as
      ;; "changed" again and rewrites them (a harmless phantom version bump).
      ;; The other order risks the opposite: state says "done", so a later
      ;; run's unchanged-hash check skips re-writing files that never
      ;; actually made it to disk -- a silent, non-self-healing partial
      ;; publish. See design/blygger.md.
      (do (write-surfaces! bm new-state output-dir)
          (save-state! path new-state)
          new-state))))

(defn reseal!
  "Escape hatch: re-render cached content-html for every non-withdrawn item
  from the current bm, without touching content-md/content-hash/version. For
  when a rendering fix (not a content change) needs to reach already-published
  items -- an ordinary publish! wouldn't bump them since their hash is
  unchanged.

  Two phases, like publish!: fragments first, then threads, so a thread bakes
  its children's freshly-resealed content-html rather than a pre-reseal
  snapshot. (Prior single-pass version read child html from a fixed
  orig-items, so a thread's transclusions never picked up a reseal of its own
  children regardless of reduce-kv order.)"
  [bm output-dir & {:keys [dry-run?]}]
  (let [tag (or (config/config :blygger :tag) "blyg")
        path (state-file)
        state (load-state path)
        orig-items (:items state)
        missing? (fn [block-id]
                   (when-not (contains? bm block-id)
                     (log/warn "blyg reseal: block" block-id "not found in bm, leaving as-is")
                     true))
        ;; Phase 1: fragments (leaves + promoted children) -- threads
        ;; untouched here, handled in phase 2 once children are current.
        resealed-fragments
        (reduce-kv
         (fn [items block-id entry]
           (cond (= :withdrawn (:kind entry)) items
                 (= :thread (:kind entry)) items
                 (missing? block-id) items
                 :else
                 (assoc items block-id
                        (assoc entry :content-html
                               (item-content-html bm tag (get bm block-id)
                                                   :attribution? (not (:thread-child? entry)))))))
         orig-items
         orig-items)
        ;; Phase 2: threads, reading children's content-html from
        ;; resealed-fragments so transclusions bake the fresh version.
        items
        (reduce-kv
         (fn [items block-id entry]
           (cond (= :withdrawn (:kind entry)) items
                 (not= :thread (:kind entry)) items
                 (missing? block-id) items
                 :else
                 (let [block (get bm block-id)
                       kids (body-children bm block)]
                   (assoc items block-id
                          (assoc entry :content-html
                                 (thread-content-html bm tag resealed-fragments block kids))))))
         resealed-fragments
         resealed-fragments)
        new-state (assoc state :items items)]
    (if dry-run?
      new-state
      (do (write-surfaces! bm new-state output-dir) ;surfaces before state -- see note in publish!
          (save-state! path new-state)
          new-state))))
