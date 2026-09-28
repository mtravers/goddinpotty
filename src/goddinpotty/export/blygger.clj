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
  "Blocks tagged #<tag>, published regardless of whether the site's
  entry-tag graph walk would otherwise reach them -- only an explicit exit
  tag (#Private/#ExitPoint/etc) excludes one. Logs (and drops) any #<tag>
  block an exit tag excludes, rather than silently publishing -- or silently
  ignoring the author -- either way."
  [bm tag]
  (keep (fn [block]
          (cond (not (tag-block? tag block))
                nil
                (excluded? bm block)
                (do (log/warn "blyg: skipping" (:id block) "- excluded:"
                               (bd/privacy-exit-point-why bm block))
                    nil)
                :else block))
        (vals bm)))

(defn- body-children
  [bm block]
  (->> (:children block) (map bm) (remove (partial excluded? bm))))

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

(defn- abs-page-url
  [title]
  (str (config/config :real-base-url) (utils/clean-page-title title)))

(defn- md-page-link
  [title & [link-text]]
  (format "[%s](%s)" (or link-text title) (abs-page-url title)))

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
                           (if (= name tag) "" (md-page-link name (str "#" name))))
                :alias (let [[_ text target] (r/parse-alias (second p))]
                         (if (str/starts-with? target "[[")
                           (md-page-link (utils/remove-double-delimiters target) text)
                           (second p)))
                (:italic :bold) (second p)
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

(defn- item-content-md
  [bm tag block]
  (let [own (parsed->blyg-md tag (:parsed block))
        kids (body-children bm block)]
    (str/join "\n\n"
              (remove str/blank? (cons own (map (partial item-content-md bm tag) kids))))))

;;; Attribution line prepended once, at the top level -- not part of the
;;; recursive item-content-md above (which also handles the item's own
;;; children), or every child would repeat it.
(defn- item-full-content-md
  [bm tag block]
  (let [body (item-content-md bm tag block)]
    (if-let [page (source-page bm block)]
      (str "From " (md-page-link (:title page)) "\n\n" body)
      body)))

;;;; ⩇⩆⩇ Content: html ⩇⩆⩇

;;; Reuse the site's real renderer (images, aliases, embeds, headings, ...)
;;; for fidelity; only surgery is blanking out the tag node itself.
(defn- strip-tag-parsed
  [tag parsed]
  (walk/postwalk (fn [node]
                   (if (and (vector? node) (= :hashtag (first node))
                            (= tag (utils/parse-hashtag (second node))))
                     ""
                     node))
                 parsed))

(defn- item-content-hiccup
  [bm tag block]
  (let [stripped (assoc block :parsed (strip-tag-parsed tag (:parsed block)))
        own [:p (r/block-hiccup stripped bm)]
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

;;; Same attribution line as item-full-content-md, prepended once at the top
;;; level -- see there for why it's not folded into the recursive builder.
(defn- item-full-content-hiccup
  [bm tag block]
  (let [body (item-content-hiccup bm tag block)]
    (if-let [page (source-page bm block)]
      [:div [:p.source-page "From " [:a {:href (abs-page-url (:title page))} (:title page)]] body]
      body)))

(defn- item-content-html
  [bm tag block]
  (-> (item-full-content-hiccup bm tag block)
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
  content-hash is unchanged (no publish event -- no version bump)."
  [existing kind content-md content-html now]
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
           :changelog (conj (:changelog existing) {:version v :at now :note nil}))))

;;;; ⩇⩆⩇ Protocol surface builders ⩇⩆⩇

(defn- overall-updated
  [items]
  (or (last (sort (map :updated (vals items)))) (now-iso)))

(defn- item-json
  [origin entry]
  (let [withdrawn? (= :withdrawn (:kind entry))]
    {:blyg "0.2"
     :id (:blyg-id entry)
     :kind (name (:kind entry))
     :origin origin
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
                       (:changelog entry))}))

(defn- manifest
  [origin items updated]
  {:blyg "0.2"
   :level 1
   :generator "goddinpotty-blyg/0.1"
   :site origin
   :title (or (config/config :blygger :title) (config/config :short-title))
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
  withdrawn item contributes only its withdrawal event (§7)."
  [items feed-window]
  (->> items
       vals
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

;;; Minimal, non-fancy permalink page -- SHOULD serve HTML (§4), but the
;;; reference client's masthead/carousel/blogroll presentation (css-contract.md)
;;; is that client's promise, not the protocol's, and is out of scope here.
(defn- permalink-hiccup
  [origin entry]
  (let [withdrawn? (= :withdrawn (:kind entry))]
    [:html
     [:head [:meta {:charset "utf-8"}] [:title (str "blyg " (:blyg-id entry))]]
     [:body
      [:div.blyg
       [:article {:class (str "fragment" (when withdrawn? " withdrawn"))}
        [:div.item-content (if withdrawn? "[withdrawn]" (hiccup2/raw (:content-html entry)))]
        [:p.version-line "v" (:version entry)]
        [:p [:a {:href (str origin "items/" (:blyg-id entry) ".json")} "JSON"]
         " · " [:a {:href origin} "feed"]]]]]]))

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
  [state output-dir]
  (let [origin (blyg-origin)
        base-dir (str (str/replace output-dir #"/$" "") "/" (mount-path))
        items (:items state)
        updated (overall-updated items)
        title (or (config/config :blygger :title) (config/config :short-title))
        feed-window (or (config/config :blygger :feed-window) 50)]
    (ensure-mount-dir! base-dir)
    (utils/write-json (str base-dir "blyg.json") (manifest origin items updated))
    (utils/write-json (str base-dir "items/index.json") (archive-index items updated))
    (doseq [entry (vals items)]
      (utils/write-json (str base-dir "items/" (:blyg-id entry) ".json") (item-json origin entry))
      (let [dir (str base-dir "f/" (:blyg-id entry) "/")]
        (fs/mkdirs dir)
        (spit (str dir "index.html") (str (hiccup2/html (permalink-hiccup origin entry))))))
    (spit (str base-dir "feed.xml") (feed-xml origin title items feed-window))
    (log/info "blyg: wrote" (count items) "items to" base-dir)))

;;;; ⩇⩆⩇ Top level ⩇⩆⩇

(defn publish!
  "Scan `bm` for #<tag> blocks the site already publishes and (re)write the
  blyg surface into `output-dir`. Safe to call repeatedly -- version bumps
  only on content change. Blocks previously published but no longer tagged
  are left alone (and warned about) unless :withdraw? true. :dry-run? true
  computes and logs the new state without writing anything."
  [bm output-dir & {:keys [withdraw? dry-run?]}]
  (when-not (config/config :blygger :enabled?)
    (throw (ex-info "blyg is not enabled (:blyg :enabled? in config)" {})))
  (let [tag (or (config/config :blygger :tag) "blyg")
        path (state-file)
        state (load-state path)
        now (now-iso)
        candidates (blyg-blocks bm tag)
        current-ids (set (map :id candidates))
        prior-ids (set (keys (:items state)))
        withdrawn-ids (set/difference prior-ids current-ids)
        _ (when (seq withdrawn-ids)
            (if withdraw?
              (log/warn "blyg: withdrawing" (count withdrawn-ids) (str "item(s) no longer tagged #" tag ":") withdrawn-ids)
              (log/warn "blyg:" (count withdrawn-ids) (str "previously-published item(s) are no longer tagged #" tag)
                        "-- left untouched; pass :withdraw? true to withdraw them:" withdrawn-ids)))
        items-1 (reduce (fn [items block]
                          (update items (:id block)
                                  next-entry :fragment
                                  (item-full-content-md bm tag block)
                                  (item-content-html bm tag block)
                                  now))
                        (:items state)
                        candidates)
        items-2 (if withdraw?
                  (reduce (fn [items id]
                            (if (= :withdrawn (:kind (get items id)))
                              items
                              (update items id withdraw-entry now)))
                          items-1
                          withdrawn-ids)
                  items-1)
        new-state (assoc state :items items-2)
        changed (filter (fn [[id e]] (not= e (get-in state [:items id]))) items-2)]
    (if dry-run?
      (do (log/info "blyg dry-run:" (count candidates) "candidate(s)," (count changed)
                    "item(s) would change (new version or new item), no files written")
          new-state)
      ;; Surfaces before state, deliberately: if this crashes partway, an
      ;; unsaved state means the next run recomputes these same items as
      ;; "changed" again and rewrites them (a harmless phantom version bump).
      ;; The other order risks the opposite: state says "done", so a later
      ;; run's unchanged-hash check skips re-writing files that never
      ;; actually made it to disk -- a silent, non-self-healing partial
      ;; publish. See design/blygger.md.
      (do (write-surfaces! new-state output-dir)
          (save-state! path new-state)
          new-state))))

(defn reseal!
  "Escape hatch: re-render cached content-html for every non-withdrawn item
  from the current bm, without touching content-md/content-hash/version. For
  when a rendering fix (not a content change) needs to reach already-published
  items -- an ordinary publish! wouldn't bump them since their hash is
  unchanged."
  [bm output-dir & {:keys [dry-run?]}]
  (let [tag (or (config/config :blygger :tag) "blyg")
        path (state-file)
        state (load-state path)
        items (reduce-kv
               (fn [items block-id entry]
                 (cond (= :withdrawn (:kind entry)) items
                       (not (contains? bm block-id))
                       (do (log/warn "blyg reseal: block" block-id "not found in bm, leaving as-is")
                           items)
                       :else
                       (assoc items block-id
                              (assoc entry :content-html
                                     (item-content-html bm tag (get bm block-id))))))
               (:items state)
               (:items state))
        new-state (assoc state :items items)]
    (if dry-run?
      new-state
      (do (write-surfaces! new-state output-dir) ;surfaces before state -- see note in publish!
          (save-state! path new-state)
          new-state))))
