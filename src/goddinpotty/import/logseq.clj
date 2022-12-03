(ns goddinpotty.import.logseq
  (:require [goddinpotty.utils :as utils]
            [goddinpotty.database :as db]
            [goddinpotty.rendering :as rendering]
            [goddinpotty.config :as config]
            [me.raynes.fs :as fs]
            [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [clojure.java.shell :as sh]
            [clojure.tools.logging :as log]
            )
  )

(defn publish-images
  [logseq-dir]
  (doseq [file @rendering/published-images]
    (try
      ;; this tree-hopping is ugly
      (fs/copy+ (fs/expand-home (str logseq-dir "/pages/" file))
                ;; yes we have to dip down into hierarchy
                (fs/expand-home (str (:output-dir (config/config)) "/assets/" file)))
      (catch Throwable e
        (log/error e "Error copying file" )))))

;;; TODO Not Logseq specific
(defn publish-assets
  []
  (doseq [file (fs/list-dir "resources/public")]
    (fs/copy+ file
              (str (fs/expand-home (:output-dir (config/config))) "/assets/" (fs/base-name file)))))

(defn post-generation
  []
  (publish-images (get-in (config/config) [:source :repo]) )
  (publish-assets))

;;; nbb dumps have no :block/children, just parent
;;; → multitool
(defn add-children
  [parent-att child-att db]
  (reduce-kv (fn [acc key item]
               (if-let [parent (get item parent-att)]
                 (update-in acc [parent child-att] conj key)
                 acc))
             db
             db))

;;; Uses the :left property to order the children properly. Relies that :left of leftmost child
;;; points to parent.
;;; TODO Bug - Can introduce nils to :children slot, causing downstream problems
(defn- order-children
  ([db]
   (u/map-values
    (fn [block]
      (update block :children #(order-children db (set %) (:id block))))
    db))
  ([db child-ids left]
   (cond (nil? left) child-ids
         (empty? child-ids) []
         :else
         (let [next (u/some-thing (fn [child-id]
                                    (= left (get-in db [child-id :left])))
                                  child-ids)]
           (cons next (order-children db (disj child-ids next) next))
         ))))

(comment
  (frequencies (mapcat (comp keys :block/properties) nbb))
  {:updated-at 18659,
   :created-at 16777,
   :id 18523,
   :title 1855,

   :alias 162,
   :aliases 2,

   :heading 704
   :tags 10,
   :template-including-parent 3,
   :date 21,
   :issn 4,
   :publisher 2,
   :issue 8,
   :proceedings-title 2,
   :publication 9,
   :public 2,
   :type 10,
   :template 3,
   :icon 2,
   :doi 4,

   :pages 10,
   :volume 6,
   :topics 8,
   :item-type 12,
   :access-date 6,

   :original-title 12,
   :language 6,
   :class 2,
   :url 15,
   :short-title 2,
   :publication-title 6,

   :journal-abbreviation 2,
   :authors 22,
   :library-catalog 10,
   :links 12,
})

;;; TODO multitool unlist             
(defn uncoll [thing]
  (if (and (coll? thing) (= 1 (count thing)))
    (first thing)
    thing))

;;; TODO do something with :block/refs and/or :block/path-refs probably?
;;; path-refs seems to be union of refs and parent?
(defn logseq-nbb->blocks-base
  [blocks]
  (->> blocks
       (map (fn [block]
              ;; Sanity check, this condition causes problems further down (order-children produces nils)
              (assert (not (= (:db/id block) (:db/id (:block/parent block))))
                      (str "Block is its own parent: " (:db/id block)))
               ;; TODO reexamine this – probably only pages should have title?
               {:title (or
                        ;; uncoll because sometimes this is a set for no good reason
                        (uncoll (get-in block [:block/properties :title]))
                        (:block/original-name block) ;??? Not sure what actual semnatics are, but this is often better TODO should name be alias?
                        (:block/name block))
                :id (:db/id block) ;note: has to be id so refs work
                :uid (str (:block/uuid block))
                :content (:block/content block) ;TODO strip out properties
                :edit-time (utils/coerce-time (or (get block :block/updated-at)
                                                  (get-in block [:block/properties :updated-at])))
                :create-time (utils/coerce-time (or (get block :block/updated-at)
                                                    (get-in block [:block/properties :created-at])))

                :parent (get-in block [:block/parent :db/id])
                :left (get-in block [:block/left :db/id])
                :page? (boolean (:block/name block)) ;???
                :page (:db/id (:block/page block))
                :alias (get-in block [:block/properties :alias])
                ;; TODO not used yet – we pull out the useful ones, maybe don't need
                :properties (get block :block/properties)
                }
              ))
       (u/index-by :id)
       (add-children :parent :children)
       (order-children)
       ))

;;; Requires nbb-logseq to be installed
(defn nbb-query
  [graph-name query]
  (let [{:keys [exit out err]}
        ;; TODO ugly and maybe antiperformant that this returns a string. But sh/sh is incapable of
        ;; writing to a file? Takes about a minute for my big graph, but most of that is in nbb, not
        ;; parse.
        (sh/sh "nbb-logseq"
               "resources/nbb-query.cljs" 
               graph-name
               (str query))]
    (if (= exit 0)
      (read-string out)
      (throw (ex-info err {:exit exit :err err})))))

(def extract-query
  ;; Note :file/content is also available, not really needed so not extracted here.
  '[:find (pull ?b [* {:block/file [:db/id :file/path]}])
    :where [?b :block/uuid _]])

(defn extract-page-query
  [id]
  (u/de-ns
   `[:find (pull ?b [* {:block/file [:db/id :file/path]}])
     :where [?b :block/page ~id]]  ))

(defn get-page-blocks
  [graph-name id]
  (map first (nbb-query graph-name (extract-page-query id))))

(defn nbb-extract
  [graph-name]
  (map first
       (nbb-query graph-name extract-query)))

;;; dev only for now
(defn nbb-datoms
  [graph-name]
  (group-by first (nbb-query graph-name '[:find ?a ?b ?c :where [?a ?b ?c]])))


(defn snapshot
  [thing file]
  (ju/schppit file thing)
  thing)

(defn produce-bm
  [config]
  (-> config
      (get-in [:source :graph])
      nbb-extract
      (snapshot "nbb-extract.edn")
      logseq-nbb->blocks-base
      db/build-db-1
      ;; Disabled for now
      #_ get-edit-times                  
      ;; Going to guess this is no longer necessary?
      #_ bd/add-empty-pages
      db/generate-inverse-refs ;have to redo this after add-empty-pages
      ))
