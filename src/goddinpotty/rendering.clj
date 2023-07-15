(ns goddinpotty.rendering
  (:require [goddinpotty.config :as config]
            [goddinpotty.parser :as parser]
            [goddinpotty.batadase :as bd]
            [goddinpotty.database :as db]
            [goddinpotty.graph :as graph]
            [goddinpotty.utils :as utils]
            [goddinpotty.context :as context]
            [goddinpotty.endure :as e]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.dev :as udev]
            [clojure.tools.logging :as log]
            )
  )

;;; Turn parsed content into hiccup. 

(declare block-content->hiccup)         ;allow recursion on this
(declare block-full-hiccup)
(declare block-full-hiccup-guts)

;;; Boostrap icons, see css and https://icons.getbootstrap.com/
(defn icon
  [name]
  [:i {:class (str "bi-" name)}])

;;; "alias" seems like a misnomer, these are mostly external links.
(defn parse-alias
  [alias-content]
  (re-find #"(?s)\[(.+?)\]\((.+?)\)" alias-content))

(defn- format-alias
  [alias-content]
  (let [[_ alias-text alias-dest] (parse-alias alias-content)
        internal? (or (= \( (first alias-dest)) (= \[ (first alias-dest)))
        alias-link (if internal?
                     (utils/clean-page-title alias-dest)
                     alias-dest)
        alias-target (case (config/config :link-new-tabs)
                       (:all true) "_blank"
                       :external (if internal? nil "_blank")
                       nil)
        alias-props (if alias-target
                      {:href alias-link :target alias-target}
                      {:href alias-link})
        content (block-content->hiccup alias-text)]
    (if internal?
      [:a alias-props content]
      [:a.external alias-props content])))

;;; Track local image files so they can be copied to output
(def published-images (atom #{}))

(defn local-path?
  [path]
  ;; TODO there must be better url regexs out there
  (not (re-matches #"[\w-]+\:(.+)" path)))

(defn maybe-publish-image
  [src]
  (when (local-path? src)
    (swap! published-images conj src)))

(defn maybe-relocate-local-image
  [source]
  (if (re-matches #"\.\./assets.*" source)
    (subs source 3)
    source))

(defn parse-image-string
  [s]
  (let [[match? label url] (re-matches #".*\[(.*?)\]\((.*?)\).*" s)]
    (when match? [url label])))

(defn parse-image-block
  [b]
  (parse-image-string (second (second (:parsed b)))))

(defn- format-image
  [image-ref-content]
  (let [[image-source alt-text] (parse-image-string image-ref-content)
        image-published (maybe-relocate-local-image image-source)
        ]
    (maybe-publish-image image-source)
    ;; Link to
    [:a.imga {:href image-published :target "_image"} ;cheap way to get expansion. TODO link highlighhting looking slightly crufty
     [:img {:src image-published :alt alt-text}]]))

(defn youtube-vid-embed
  "Returns an iframe for a YouTube embedding"
  [youtube-id]
  [:iframe {:width "560"
            :height "315"
            :src (str "https://www.youtube.com/embed/" youtube-id)
            :frameborder "0"
            :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"
            :allowfullscreen ""}])

(defn get-youtube-id
  [string]
  (or (second (re-find #"https\:\/\/youtu\.be/([\w_-]*)" string))
      (second (re-find #"https\:\/\/www.youtube.com\/watch\?v=([\w_-]*)" string))))

;;; Not used much
(defn- make-link-from-url
  [string]
  [:a.external {:href string} string])

;;; Tried to build this into instaparse parser, but couldn't make it take precedence over :bare-url
;;; Note: this only is relevant to Roam; Logseq uses {{tweet ...}} syntax
(defn twitter-url?
  [url]
  (re-matches #"https:\/\/twitter.com\/\S*" url))

;;; TODO fails for image links like https://twitter.com/lastpositivist/status/1341720934735069184/photo/1 but Logseq does these...
;;; Temp kludge solution: grep and remove "/photo/1"
;;; Actually it can change, if the backing tweet is deleted. Oh well.
(e/defn-memoized embed-twitter          ;memoize this, it doesn't change and API c
  [url]
  (try
    (let [oembed (json/read-str (slurp (str "https://publish.twitter.com/oembed?url=" url)) :key-fn keyword)]
      (:html oembed))
    (catch Throwable e
      (log/error "twitter embed failed" e)
      (make-link-from-url url))))

(defn- make-content-from-url
  [url]
  (cond (twitter-url? url)                ;TODO if there are more
        (embed-twitter url)
        (get-youtube-id url)
        (youtube-vid-embed (get-youtube-id url))
        :else
        (make-link-from-url url)))

;;; Extremely smelly. 
(defn unspan
  "Remove :span elts that are basically no-ops. Would be cleaner to not generate in the first place."
  [hiccup]
  (if (and (vector? hiccup)
           (= :span (first hiccup))
           (= 2 (count hiccup)))
    (second hiccup)
    hiccup))

(defn format-codeblock
  [spec]
  (let [[_ _lang content] (re-matches #"(?sm)```(\w*)\n(.*)```\s*" spec)]
    ;; There are packages that will do language-specific highlighting, don't care at the moment.
    [:code.codeblock content]))

(defn pagify
  [name]
  (str "[[" name  "]]"))

;;;  page can be page or page-id (requires bm)
(defn page-link
  [opage & {:keys [alias class bm current]}]     
  (let [page (if (string? opage)
               (bd/get-with-inexact-aliases bm opage) ;TODO inexact matching should be config option probably
               opage)
        alias (or alias (and (string? opage) opage)) ;argh
        page-id (:id page)
        page-title (:title page)]
    (if (and page (bd/displayed? page) page-title)
      [:a (u/clean-map
           {:href (utils/clean-page-title page-title)
            ;; TODO behavior with empties should be configurable, I keep
            ;; changing my own mind about it.
            :class (str/join " "
                             (filter identity
                                     (list (when (bd/page-empty? page) "empty")
                                           (when (= current page-id) "self")
                                           (when-not (:include? page) "excluded")
                                           class)))})
       (block-content->hiccup (or alias page-title))]
      (do
        ;; This is normal but a sign that target might want to be exposed.
        (log/warn "Ref to excluded or missing page" (pagify (or opage page-id)) "from" (context/get-context))
        [:span.empty
         (block-content->hiccup (or alias page-title))]))))

;;; Argh this is stupid
(defn page-link-by-name
  [bm page-name & rest]
  (apply page-link page-name :bm bm rest))

    ;; (cond (contains? bm page-name)
    ;;     (apply page-link page-name :bm bm rest)
    ;;     (empty? bm)                     ;this can happen when a page title contains a link. (???)
    ;;     [:span page-name]
    ;;     :else
    ;;     [:span.missing page-name]       ;In Logseq world, this happens if you have a single link to a non-existant (empty) page
    ;;     )))


(declare block-hiccup)




;;; A much easier way to do sidenotes
(declare ele->hiccup)
(defn new-sidenote
  [block-map sidenote-text]
  [:span.sidenote-container
   [:span.superscript]
   [:div.sidenote                     ;TODO option to render on left/right
    [:span.superscript.side]
    (ele->hiccup (parser/parse-to-ast sidenote-text) block-map)]])


;;; Census from AMMDI 21 Jan 2023
#_
{nil 19,
 "tweet" 365,
 "youtube" 29,
 "video" 38,
 "zotero-imported-file" 8,
 "POMO" 14,
 "mentions" 1,
 "[youtube" 1,
 "pdf" 2,
 "∆" 3,
 "alias" 3,
 "embed" 3,
 "timer" 1}

(defn double-braces [block-map command arg]
  (case command
    ("tweet" "twitter") (embed-twitter arg)
    ("youtube" "video") (if-let [youtube-id (get-youtube-id arg)]
                          (youtube-vid-embed youtube-id)
                          [:span "Non-youtube video" arg])
    "sidenote" (new-sidenote block-map arg)
    ;; TODO this usually follows a link to a zotero: url which is useless on a public page, so maybe do something more intelligent
    "zotero-imported-file" nil          
    "pdf" [:a {:href arg} "PDF"]
    "toot" [:div.toot
            (ele->hiccup (parser/parse-to-ast arg) block-map)]
    ;; Default
    (do
      (log/error "Unknown {{}} command" command arg) ;TODO should have full element
      nil)))

;;; Not strictly necessary, but makes tests come out better
(defn- maybe-conc-string
  [seq]
  (if (every? string? seq)
    [(str/join seq)]
    seq))

;;; Smelly
(defn new-head
  [thing head]
  (if (vector? thing)
    (assoc thing 0 head)
    [head thing]))
  
;;; Bootstrap requires some classes to look good. This might be extended
;;; for other things. Not sure this is right thing, but solves an immediate problem
(defn hiccup-fixups
  [hic]
  (u/substitute hic {:table :table.table.table-bordered}))

(u/defn-memoized uid-indexed
  [bm]
  (->> bm
       vals
       (u/index-by :uid)))

(def hidden-properties
  #{"title" "original-title"
    ;; You might actually want aliases, could be an option (but looks kind of fugly now)
    "alias"
    "id" "created-at" "updated-at" "public" "collapsed"
    ;; Zotero tags that aren't very interesting
    "access-date" "library-catalog"
    })

;;; Does most of the real work of rendering.
(defn ele->hiccup
  [ast-ele block-map & [block]]
  (udev/debuggable                     ;TODO for dev, but for production it should just render an error box rather than crapping out. 
   :ele->hiccup [ast-ele]
   ;; TODO this approach is broken, it hides page-refs within italics. 
   (letfn [(recurse [s]                 ;TODO probably needs a few more uses
             (ele->hiccup (parser/parse-to-ast s) block-map block))
           (nrecurse [seq]
             (mapv #(if (string? seq)
                      seq
                      (ele->hiccup % block-map block))
                   seq))] 
     (if (string? ast-ele)
       ast-ele
       (let [ele-content (second ast-ele)]
         (unspan
          (case (first ast-ele)
            nil nil
            :block-property
            (let [[_ label value] (re-matches #"\n?(.+?):: (.+)\n?" (second ast-ele))]
              (when-not (contains? hidden-properties label)
                ;; TODO provisional
                [:li [:b label] ": " (recurse value)]))

            :prop-block nil
            :heading (let [base (ele->hiccup (nth ast-ele 2) block-map)]
                       (case (count (second ast-ele))
                         1 [:h1 base]
                         2 [:h2 base]
                         3 [:h3 base]))
            :page-link (page-link-by-name block-map (utils/remove-double-delimiters ele-content))
            
            :block-ref (let [ref-block (get (uid-indexed block-map) (-> ele-content
                                                                        str/trim
                                                                        utils/remove-double-delimiters))]
                             [:div.block-ref                   ;render a real blockref
                              (block-hiccup ref-block block-map)])
            :hashtag (let [ht (utils/parse-hashtag ele-content)]
                       (or (bd/special-hashtag-handler block-map ht block)
                           (page-link-by-name block-map ht)))
            :strikethrough [:s (recurse (utils/remove-double-delimiters ele-content))]
            :highlight [:mark (recurse (utils/remove-double-delimiters ele-content))]
            :italic `[:i ~@(maybe-conc-string (nrecurse (rest ast-ele)))]
            :bold `[:b ~@(maybe-conc-string (nrecurse (rest ast-ele)))]
            :alias (format-alias ele-content)
            :image (format-image ele-content)
            :code-line [:code (utils/remove-n-surrounding-delimiters 1 ele-content)]
            :code-block (format-codeblock ele-content)

            :bare-url (make-content-from-url ele-content)
            :blockquote (new-head (ele->hiccup ele-content block-map block) :blockquote)
                                        ;ast-ele
            :block (let [contents (u/mapf #(ele->hiccup % block-map block) (rest ast-ele))
                         ;; bring this property up to a useful level
                         ;; TODO should perhaps be done elsewhere and/or in a more general way
                         class (some :class (:dchildren block))]
                     (cond (empty? contents)
                           nil
                           class
                           `[:span {:class ~class} ~@contents]
                           (> (count contents) 1) 
                           `[:span ~@contents]
                           :else
                           (first contents)))
            :hr [:hr]
            ;; See https://www.mathjax.org/ This produces an inline LaTex rendering.
            :latex [:span.math.display (str "\\(" (utils/remove-double-delimiters ele-content) "\\)")]
            ;; TODO better error handling
            :hiccup (hiccup-fixups (u/ignore-report (read-string ele-content)))
            :doublebraces (let [[_ command arg] (re-find #"\{\{(\S+) (.+)\}\}" ele-content)]
                            (if command
                              (double-braces block-map command arg)
                              (log/error "Unknown {{}} elt " ast-ele))))))))))

;;; Used for converting things like italics in blocknames
(defn block-content->hiccup
  [block-content]
  (ele->hiccup (parser/parse-to-ast block-content) {}))

;;; Total hack because I failed to figure this out in instaparse, and its a one-shot thing...
;;; TODO fragile
(defn remove-logseq-title
  [[_ h1 title h2 :as parsed]]
  (if (and (= h1 [:hr "---"])
           (= h2 [:hr "---"])
           (re-matches #"(?m)^\ntitle\: (.*)\n$" title))
    [:block]
    parsed))

;;; In lieu of putting this in the blockmap
;;; TODO turn it back on with old sidenote feature is history
(defn block-hiccup
  "Convert Roam markup to Hiccup"
  [block block-map]
  ;; When an excluded block is target of a :block-ref, it needs to be parsed here
  ;; Note: this might produce inconsistent results, if a block is excluded early but included here, its referernces won't be chased. I guess a correct solution would require following :block-refs at parse time or ref time. Argh.
  (when-let [parsed (or (:parsed block) (:parsed (db/parse-block block)))]
    (let [parsed (remove-logseq-title parsed)
          basic (ele->hiccup parsed block-map block)]
      (cond (and (:heading block) (> (:heading block) 0))
            [(keyword (str "h" (:heading block))) basic]
            (fn? basic)                 ;#incoming uses this hack, maybe others
            (basic block-map)
            :else
            basic))))

(defn- block-full-hiccup-guts
  [block-id block-map & [depth]]
  (let [depth (or depth 0)
        block (bd/get-with-aliases block-map block-id)]
    (when (bd/displayed? block)
      ;; TODO with Logseq, block-id is useless for long-term persistent links; ids chane with every reindex. Need to rethink. A synthetic id based on page name and block position maybe.
      [:ul {:id block-id :class (cond (not (bd/included? block)) "excluded"
                                      (< depth 2) "nondent" ;don't indent the first 2 levels
                                      :else "")}
       "\n"
       [:li.block
        ;; TODO Would be cool if could jigger logseq into viewing the page, but a no-op for now
        #_
        (when (config/config :dev-mode)
          [:a.edit {:href (roam-url block-id)
                    :target "_roam"}
           (icon "pencil")
           ])           
        ;; ugly, trying something different
        #_
        (when-not (bd/included? block)
          [:span.edit 
           (icon "file-lock2")])
        (when-not (:page? block)        ;Page content is title and rendered elsewhere
          (block-hiccup block block-map))]
       (map #(block-full-hiccup % block-map (inc depth))
            (:children block))])))

;;; The real top-level call
(defn block-full-hiccup
  [block-id block-map & [depth]]
  (block-full-hiccup-guts block-id block-map depth))

(defn page-hiccup
  [block-id block-map]
  (block-full-hiccup block-id block-map))

(defn url?
  [s]
  (re-matches #"(https?:\/\/(?:www\.|(?!www))[a-zA-Z0-9][a-zA-Z0-9-]+[a-zA-Z0-9]\.[^\s]{2,}|www\.[a-zA-Z0-9][a-zA-Z0-9-]+[a-zA-Z0-9]\.[^\s]{2,}|https?:\/\/(?:www\.|(?!www))[a-zA-Z0-9]+\.[^\s]{2,}|www\.[a-zA-Z0-9]+\.[^\s]{2,})" s))

(defn block-local-text
  "just the text of a block, formatting removed"
  [block-map block]
  (letfn [(text [thing]
            (cond (string? thing)
                  (if (url? thing)
                    ()
                    (list thing))
                  (map? thing)
                  ()
                  (vector? thing)
                  (mapcat text (rest thing))
                  :else
                  ()))]
    (str/join "" (text (block-hiccup block block-map)))))

;;; Used for search index
(defn block-full-text
  [block-map block]
  (if (bd/displayed? block)
    (str/join " " (cons (block-local-text block-map block)
                        (map #(block-full-text block-map (get block-map %))
                             (:children block))))
    ""))

;;; Include bare HTML from assets folder
;;; TODO not currently used (was going to use it to include Paypal button),
;;;    and needs a bit of work (the parameter is rendered)
;;; TODO To be useful, might need a version that puts stuff at top-level of html file rather than in the flow (for scripts etc)
(bd/register-special-tag
 "include"
 (fn [bm block]
   (let [included (str/trim (get-in block [:parsed 2]))
         path (str (get-in (config/config) [:source :repo]) "/assets/" included )
         contents (slurp path)]
     ;; Seems like this just works
     contents)))
