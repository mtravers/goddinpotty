(ns goddinpotty.batadase
  (:require [goddinpotty.utils :as utils]
            [goddinpotty.config :as config]
            [org.parkerici.multitool.core :as u]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            ))

;;; Database accessors. The name is a PROTEST against the feature of Clojure I hate most, rigid limits on namespace reference

(defn block? [x]
  (and (map? x)
       (:id x)))

(defn assert-block
  [x]
  (or (block? x)
      (throw (ex-info "Not a block" {:thing x}))))

;;; included? means reachable from an entry point
;;; displayed? means actually generated. Usually these are the same, except when the :unexcluded? config is set, meaning we want to see everything, included or not.

(defn included?
  ([block]
   (assert-block block)
   (:include? block))
  ([block-map block-id]
   (included? (get block-map block-id))))

(u/defn-memoized special-tags
  []
  (set (concat (config/config :exit-tags)
               (config/config :entry-tags))))

;;; All legit names of block, main and aliases
(defn block-names
  [block]
  (if (:title block)
    (conj (:alias block) (:title block))
    (:alias block)))

(defn special-tag-block?
  [block]
  (some (special-tags) (block-names block)))

;;; Logseq makes a lot of these, I think for links to unrealized pages. They suck and the interfere with aliases, so weeding them early
(defn block-empty?
  [block]
  (and (nil? (:parent block))
       (u/nullish? (:content block))
       (u/nullish? (:children block))
       (not (:special? block))
       (not special-tag-block?)))

(defn displayed?
  ([block]
   (assert-block block)
   (if (config/config :unexclude?)
     true
     (and (:include? block)
          ;; TODO yet another definition of empty?
          (or (:content block)
              (:special? block)
              (not (empty? (:children block)))
              ;; TODO should only count included/displayed blocks, but needs bm then
              (> (count (:ref-by block)) 1) ;if a page has >1 ref, it is worth generating (TODO maybe this should be option)

              ))))
  ([block-map block-id]
   (displayed? (get block-map block-id))))

(defn- get-linked-references
  [block-id block-map]
  (filter #(get-in block-map [% :id])      ;trying to filter out bogus entries, not working
          (get-in block-map [block-id :ref-by])))

(defn get-displayed-linked-references
  [block-id block-map]
  (filter (partial displayed? block-map)
          (get-linked-references block-id block-map)))

;;; Some new accessors

(def descendents
  (u/transitive-closure :dchildren))

;;; TODO DWIMish. Maybe turn into a macro and use everywhere. Or put the map in a global, I don't care if that's bad Clojure
(defn coerce-block
  [b block-map]
  (if (block? b)
    b
    (or (get block-map b)
        (throw (ex-info "Not a block" {:thing b})))))

(defn block-parent
  [block-map block]
  (let [block (coerce-block block block-map)]
    (and (:parent block)
         (get block-map (:parent block)))))

(declare page-parent)

;;; Like block parent, but navigates page hierarchies.
;;; So #Private in page [[foo]] will hide page [[foo/bar]].
(defn block-page-parent
  [block-map block]
  (let [block (coerce-block block block-map)]
    (or (and (:parent block)
             (get block-map (:parent block)))
        (page-parent block block-map))))

(defn ancestors0
  [block-map block]
  (cons block (if-let [parent (block-parent block-map block)]
                (ancestors0 block-map parent)
                nil)))

(defn block-ancestors
  [block-map block]
  (let [block (coerce-block block block-map)]
    (rest (ancestors0 block-map block))))

(defn block-children
  [block-map block]
  (map block-map (:children block)))

(defn block-descendents
  [block]
  ((u/transitive-closure :dchildren) block))

(defn block-contains?
  [b1 b2]
  (contains? (set (map :id (descendents b1))) (:id b2)))

(defn forward-page-refs
  "Forward page refs. Returns set of ids"
  [page]
  (apply clojure.set/union
         (map :refs (filter displayed? (block-descendents page)))))

(defn block-page
  [block-map block]
  (let [block (coerce-block block block-map)]
    (cond (:page? block) block
          (:page block) (get block-map (:page block))
          :else
          (if-let [parent (block-parent block-map block)]
            (do
              ;; This can happen, solution I think is to rebuild logseq db
              (assert (not (= parent (:id block))) (str "Block is its own parent: " parent))
              (block-page block-map parent))
            block))))

(defn backward-page-refs
  [bm page]
  (map :id
       (filter displayed?
               (map (comp (partial block-page bm) bm)
                    (:ref-by page)))))

(defn page-refs
  [bm page]
  (set/union (forward-page-refs page)
             (backward-page-refs bm page)))

(defn pages
  [block-map]
  (filter :page? (vals block-map)))

(defn included-blocks
  [block-map]
  (filter included? (vals block-map)))

(defn included-pages
  [block-map]
  (filter included? (pages block-map)))

(defn displayed-pages
  [block-map]
  (->> block-map
       pages
       (filter displayed?)))

(defn displayed-blocks
  [block-map]
  (filter displayed? (vals block-map)))

(defn displayed-regular-pages
  [block-map]
  (remove :special? (displayed-pages block-map)))



(u/defn-memoized alias-map
  "Return map of aliases to page blocks"
  [bm]
  (u/collecting-merge
   (fn [collect]
     (doseq [block (vals bm)]
       (when-not (block-empty? block)
         (doseq [alias (block-names block)]
           (collect {alias (block-page bm block)})
           ))))))

(u/defn-memoized with-aliases
  [bm]
  (merge bm (alias-map bm)))

(defn inexact-match-string
  [s]
  (str/lower-case s))                   ;TODO also punc removal probably

(u/defn-memoized with-inexact-aliases
  [bm]
  (let [a (with-aliases bm)]
    (reduce-kv (fn [bm name block]
                 (if (string? name)
                   (let [inexact (inexact-match-string name)]
                     (if (contains? bm inexact)
                       bm
                       (assoc bm inexact block)))
                   bm))
               a
               a)))

(defn tagged?
  [block-map block tag]
  (let [tag-id (:id (get (with-aliases block-map) tag))]
    (when tag-id
      (or (contains? (:refs block) tag-id)
          ;; This implements the somewhat weird convention that tags are done in contained elts, eg
          ;; - Some private stuff
          ;;   - #Private
          ;; partly for historical reasons and partly so pages can be tagged
          (some #(contains? (:refs %) tag-id)
                (block-children block-map block))))))

;;; True if block or any of its containers have tag. Including parent pasges in page hierarchy
(defn tagged-or-contained?
  [block-map block tag]
  (and block
       (or (tagged? block-map block tag)
           (tagged-or-contained? block-map (block-page-parent block-map block) tag))))

;;; These are the top-level entrypoints, other than those defined by tags. Crude at the moment
(defn advertised-page-names
  []
  (conj
   (set (map (fn [e]
              (if (vector? e) (second e) e))
             (config/config :right-navbar)))
   (config/config :main-page)))

(defn entry-point?
  "Determines whether or not a given page is tagged with #EntryPoint in its first child block"
  [block-map block]
  ;; TODO this should be done in logseq import, like convert this to a pseudo-tag
  (or (:public? block)                  ;the logseq block property
      (some #(tagged? block-map block %)
            (config/config :entry-tags))
      (get (advertised-page-names) (:title block))))

(defn entry-points
  [block-map]
  (filter (partial entry-point? block-map) (pages block-map)))

(def daily-notes-regex #"(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d+.., \d+")

(defn daily-notes-page?
  [page]
  (when-let [title (or (:title page) (:id page))]
    (when (string? title)               ;TODO shouldn't be necessary, but 
      (re-matches daily-notes-regex title ))))

(defn daily-notes?
  [block-map block]
  (daily-notes-page? (block-page block-map block)))

(defn exit-point?
  [block-map block]
  (or (:excluded? block)
      (some #(tagged-or-contained? block-map block %)
            (config/config :exit-tags))))

;;; Temp
(def min* (partial u/min-by identity))
(def max* (partial u/max-by identity))

;;; Prob needs to deal with missing data
;;; I suppose the median time might be more informative – or an Ed Tufte minigraph

;;; Some bogus dates creep in somehow, this removes them (TODO figure this out better)
(def earliest-date  #inst "2020-01-01T00:00")

(defn date-range [page]
  (let [blocks (block-descendents page)
        visible-blocks (filter displayed? blocks)
        visible-dates (filter (partial u/<* earliest-date)
                              ;; TODO should use both
                              (map (some-fn :edit-time :create-time) visible-blocks))]
    [(min* visible-dates) (max* visible-dates)]))

;;; TODO these are wrong because aliases get counted twice. Fuck.
;;; Pretty clear this is fucked; need a single bm and a separate naming/aliasing map. Fuck. 
(defn stats [bm]
  {:blocks {:total (count bm)
            :published (count (filter :include? (vals bm)))}
   :pages {:total (count (pages bm))
           :published (count (filter :include? (pages bm)))}})


;;; These could be part of the map but it's easier this way

(u/defn-memoized edit-time
  [page]
  (second (date-range page)))

(u/defn-memoized size
  [page]
  (reduce +
          (count (:content page ""))
          (map size
               (filter displayed? (:dchildren page)))))

;;; Used to gray out and disable links. 
#_
(defn page-empty?
  [page]
  (and (not (:special? page))
       (< (- (size page)
             (count (:title page)))
          10)))

(defn page-empty?
  [page]
  (not (displayed? page)))

(defn expand-to [block-map block minsize]
  (cond (>= (size block) minsize)
        block
        (nil? (:parent block))
        block
        :else
        (expand-to block-map (get block-map (:parent block)) minsize)))

(defn leaf?
  [block]
  (empty? (:children block)))

;;; Important fn that defines the inclusion graph.
(defn all-refs [block]
  (set/union
   (set (:children block))
   (set (:refs block))
   (set (:ref-by block))
   (set (and (:parent block) (list (:parent block))))))

;;; Special tag handling. Here for no very good reason

(def special-tags (atom {}))

(defn register-special-tag
  [tag handler]
  (swap! special-tags assoc tag handler))

(defmacro with-error-logging
  "Execute `body`, if an exception occurs, log a message and continue"
  [& body]
  `(try (do ~@body)
        (catch Throwable e#
          (log/error e# "ignored"))))

(defn special-hashtag-handler
  [bm ht block]
  #_ (when (contains? @special-tags ht) (prn :ht ht (:id block)))
  (when-let [handler (get @special-tags ht)]
    (with-error-logging
      (handler bm block))))

;;; Redone now that we create parents
;;; TODO This would be a good place to add flags that cause the incoming links to render as main body
(defn add-empty-pages
  [bm]
  (u/map-values
   (fn [block]
     (if (and (:include? block)
              (not (:parent block))
              (not (:page? block)))
              ;; If there arent multiple incoming links, really no point in having a page
       (if (> (count (:ref-by block)) 1)
         (do
           (prn :add-empty-page (:id block))
           (assoc block :page? true :title (:id block)))
         (assoc block :include? false))
       block))
   bm))

(defn get-with-aliases
  [bm page-name]
  (get (with-aliases bm) page-name))

(defn get-with-inexact-aliases
  [bm page-name]
  (get (with-inexact-aliases bm) (inexact-match-string page-name)))

(defn get-with-inexact-aliases-warn
  [bm page-name]
  (let [res (get-with-inexact-aliases bm page-name)]
    (when res
      (log/warn page-name "inexact match to" (:title res))
      res)))

;;; → Multitool?  (Guess not, kind of special purpose)
(defn vec->maps
  [v]
  (if (empty? v)
    nil                                 ;Note: other values won't work!
    {(first v) (vec->maps (rest v))}))

;;; Handles multilevel 
(defn- page-hierarchies-1
  [page-names]
  (u/collecting-merge
   (fn [collect]
     (doseq [title page-names]
       (when (re-find #"/" title)
         (collect (vec->maps (str/split title #"/"))))))))

(defn page-hierarchies ;only need to compute this once
  [bm]
  (page-hierarchies-1 (map :title (pages bm))))

(defn page-parent
  [page bm]
  (let [parent-title (and (:title page) (second (re-find #"^(.*)/(.*?)$" (:title page))))]
    (get-with-aliases bm parent-title)))

(defn page-in-hierarchy?
  [page bm]
  (or (page-parent page bm)
      (get (page-hierarchies bm) (:title page))))  ;top page


;;; Table o' contents

;;; This must exist? core.match, but not quite
;;; https://github.com/dcolthorp/matchure

;;; Note: this is minimal, and in some cases just wrong, but good enough
;;; → multitool
(defn str-match
  [pat thing]
  (cond (and (list? pat) (= '? (first pat)))
        (if thing
          {(keyword (second pat)) thing}
          nil)
        (and (sequential? pat) (sequential? thing))
        (reduce (fn [a b] (and a b (merge a b)))
                {}
                (map str-match pat (u/extend-seq thing)))
        (= pat thing) {}
        :else nil))

;;; Page table of contents generation
(defn- toc-1
  [block]
  (let [head (when-let [{:keys [depth content]}
                        (and (displayed? block)
                             (str-match '[:block [:heading (? depth) (? content)]]
                                        (:parsed block)))]
               [(count depth) (:id block)]) 
        rest
        (u/mapf toc-1 (:dchildren block))]
    (if head
      (cons head rest)
      (if (empty? rest)
        nil
        rest))))

(defn toc
  [block]
  (partition 2 (flatten (toc-1 block))))


          
          
          
        
