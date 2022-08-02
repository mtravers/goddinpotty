(ns goddinpotty.database
  (:require [goddinpotty.parser :as parser]
            [goddinpotty.batadase :as bd]
            [goddinpotty.utils :as utils]
            [goddinpotty.config :as config]
            [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [clojure.set :as set]
            [clojure.walk :as walk]
            ))

;;; Database construction.  See batadase for accessors

(defn- get-block-id
  [block-json]
  (or (:title block-json)
      (:uid block-json)))

(defn- block-properties
  [block-json]
  {:id (get-block-id block-json)       ;TODO note: having this in the map can simplify some stuff
   :children (map :uid (:children block-json))
   :content (or (:string block-json) (:title block-json))
   :heading (:heading block-json -1)
   :edit-time (when-let [time (:edit-time block-json)]
                (java.util.Date. time))
   :create-time (when-let [time (:create-time block-json)]
                  (java.util.Date. time))
   :page? (contains? block-json :title)
   })

(defn- create-basic-blocks
  [roam-json]
  (u/walk-collect
   (fn [thing]
     (when (and (get-block-id thing)
                (> (count thing) 1)) ;try to exclude :refs
       (block-properties thing)))
   roam-json))

(defn index-blocks
  [basic-blocks]
  ;; Awkward but works
  (u/self-label                         ;Add ids for newly created blocks
   :id
   (u/add-inverse
    (u/index-by :id basic-blocks)
    :children :parent
    )))

;;; Deal with Logseq page hierarchy feature. This gives pages with titles
;;; like [[Religion/Discordianism]] a link to their parent [[Religion]], for inclusion purposes.

;;; One consequence of this is that if any page of such a hierarchy is published, they all will be,
;;; unless explicitly marked #Private
(defn page-hierarchy-ref
  [page]
  (let [[_ parent _local]
        (and (:title page)           ;temp
             (re-find #"^(.*)/(.*?)$" (:title page)))]
    parent))

;;; Computes depth and inclusion, based on:
;;; - Entry points
;;; - Exit points
;;; - the graph defined by bd
;;; Does a breadth first traversal of the graph (via reduce)
(defn compute-depths
  "Computes depths from entry points"
  [block-map]
  (let [exit-point? (u/memoize-named :exit-point #(bd/exit-point? block-map (get block-map %)))]
    (loop [block-map (reduce (fn [bm entry-point]
                               (assoc-in bm [(:id entry-point) :depth] 0))
                             block-map
                             (bd/entry-points block-map))
           depth 0]
      (let [current (filter #(= (:depth %) depth) (vals block-map))
            refs (apply set/union (map bd/all-refs current))
            nbm (reduce (fn [bm ref]
                          (if (or (get-in bm [ref :depth])
                                  (exit-point? ref))
                            bm
                            (assoc-in bm [ref :depth] (+ depth 1))))
                        block-map
                        refs)]
        (if (= block-map nbm)
          block-map
          (recur nbm
                 (+ depth 1)))))))

;;; Computes :include? from :depth. TODO Seems dumb and redundant. 
(defn compute-includes
  [block-map]
  (u/map-values #(assoc % :include? (and (not (nil? (:depth %)))
                                         (not (empty? %))))
                block-map))

(defn parse-block
  [block]
  (if (:excluded? block)
    block
    (assoc block :parsed (parser/parse-to-ast (:content block))) ))

(defn parse
  [db]
  (ju/pmap-values parse-block db))

;;; Computes the value of the :refs attribute
(defn block-refs
  [block]
  (letfn [(struct-entities [struct]
            (if (string? struct)
              []
              ;; Would make sense to do some of this in parser/transform-to-ast
              (case (first struct)
                ;; :block-ref – but you don't want to follow those up for inclusion
                :block (mapcat struct-entities (rest struct))
                :hashtag [(utils/parse-hashtag (second struct))]
                :page-link [(utils/remove-double-delimiters (second struct))]
                :blockquote (struct-entities (second struct))
                :alias (if-let [v (second (re-find #"\[.*\]\(\[\[(.*)\]\]\)" (second struct)))];kluge alert
                         [v] [])
                ;; default
                (mapcat struct-entities (rest struct)))))]
    (let [base (conj (struct-entities (:parsed block))
                     (page-hierarchy-ref block))]
      (filter identity base))))


;;; Adds forward :refs field. 
(defn generate-refs
  [db]
  (ju/pmap-values (fn [block]
                    (assoc block :refs (set
                                        (u/mapf (partial bd/resolve-page-name db)
                                                (block-refs block)))))
                  db))

(defn generate-inverse-refs
  [db]
  (dissoc
   (u/self-label :id
                 (u/add-inverse-multiple db :refs :ref-by))
   nil))                                ;TODO a nil entry causes problems so removing it...should figure out where it is coming from

;;; Fixed point combinator trick for memoizing a local recursive fn 
(defn fix [f] (fn g [& args] (apply f g args)))

(defn add-direct-children
  [block-map]
  (let [direct-children
        (fn [direct-children block]
          (assoc block :dchildren 
                 (map (fn [child-id]
                        (-> child-id
                            block-map
                            (or (prn :not-found child-id)) ;Should't happen if all parts have been downloaded
                            direct-children
                            ))
                      (:children block))))
        direct-children-memoized (fix (u/memoize-named :direct-children direct-children))]
    (ju/pmap-values direct-children-memoized block-map)))

(defn exclude-blocks
  "Mark pages that can be excluded early. Right now that means journal/daily notes.
  A performance hack, should not affect the results "
  [bm]
  (if (config/config :daily-notes?)
    bm
    (u/map-values #(if (bd/daily-notes? bm %)
                     (assoc % :excluded? true)
                     %)
                  bm)))

(defn build-db-1
  [db]
  (-> db
      exclude-blocks
      parse
      generate-refs
      generate-inverse-refs
      compute-depths
      compute-includes
      add-direct-children))              ; makes it easier to use, harder to dump. This needs to be last

(defn add-uids
  [json]
  (walk/postwalk #(if (and (map? %) (not (contains? % :uid)))
                    (assoc % :uid (name (gensym "bg" )))
                    %)
                 json))

(defn build-db
  [roam-json]
  (-> roam-json
      add-uids                          ;for logseq export
      create-basic-blocks
      index-blocks
      build-db-1
      ))






      
