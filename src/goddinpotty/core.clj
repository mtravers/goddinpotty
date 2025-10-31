(ns goddinpotty.core
  (:require [goddinpotty.config :as config]
            [goddinpotty.batadase :as bd]
            [goddinpotty.html-generation :as html-gen]
            [goddinpotty.graph :as graph]
            [goddinpotty.index :as index]
            [goddinpotty.import.logseq :as logseq]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.cljcore :as ju]
            [environ.core :as env])
  (:gen-class))

;;; TODO this is a mess and should be cleaned up
(defn add-generated-pages
  [bm]
  (-> bm
      index/add-index-page
      ;; Out of service; Logseq doesn't generate per-block dates
      ;; Although could pull them from git with some work... TODO
      #_(html-gen/generated-page "New" html-gen/generate-recent-page) 
      (html-gen/generated-page "Map" html-gen/map-page)))

#_
(defn block-map-json
  [path-to-zip]
  (prn :reading-from path-to-zip)       ;TODO I suppose real logger is called for
  (-> path-to-zip
      roam/read-roam-json-from-zip
      database/roam-db
      add-generated-pages
      ))

#_
(defn block-map-edn
  [path]
  (prn :reading-from path)
  (-> path
      database/roam-db-edn
      add-generated-pages
      ))

#_
(defn pp-export
  [path-to-zip]
  (->> path-to-zip
      utils/read-roam-json-from-zip
      (ju/schppit "block-dump.edn")))

;;; BTW I want this available in all namespaces and it should be easy to do that.
(defonce last-bm (atom nil))

(defn page-ids
  []
  (keys (u/dissoc-if (fn [[_ v]] (not (:page? v))) @last-bm)))

(defn pages
  []
  (map @last-bm (page-ids)))

(defn dump
  [bm]
  (ju/schppit "dump.edn" bm))

;;; Sloooow. Dumps pages including dchildren
(defn page-dump
  []
  (ju/schppit
   (str (config/config :output-dir) "pages.edn")
   (u/clean-seq (map (fn [b] (and (:page? b) b))
                     (vals @last-bm)))))


(defn block-dump
  "Dumps included blocks or all blocks in order; the idea is this should be diffable. Also slow."
  [& {:keys [all?]}]
  (ju/schppit
   "blocks.edn"
   (into (sorted-map)
         (u/map-values #(dissoc % :dchildren)
                       (if all?
                         @last-bm
                         (u/clean-map @last-bm (comp not :include?)))))))

(defn tap
  [bm]
  (reset! last-bm bm)
  bm)

(defn delete-dir-contents
  "Like fs/delete-dir, but clears rather than deletes"
  [dir]
  (doseq [f (fs/list-dir dir)]
    (fs/delete-dir f)))

(defn reset
  []
  (u/memoize-reset!)
  (reset! goddinpotty.rendering/published-images #{})
  )

;;; Candidates for multitool
(defn delete-dir-if
  [dir]
  (if (fs/exists? dir)
    (fs/delete-dir dir)))

(defn rename-dirs
  [old new]
  (delete-dir-if new)                   ;buh bye
  (when-not (fs/rename old new)
    (throw (ex-info "Rename failed" {:old old :new new}))))

(defn replace-directory
  [old new]
  (rename-dirs old new)
  )  

(defn output-bm
  [bm]
  (let [output-dir (str (fs/temp-dir "goddinpotty"))]
    (log/info "Writing to" output-dir)
    (graph/write-page-data bm output-dir)
    (html-gen/generate-goddinpotty bm output-dir)
    (html-gen/generate-index-redir output-dir)
    ;; TODO options for writing all pages
    ;; Turning this off for now, Logseqe output is more important
    ;; Should be rationalized; html and md output should be modules
    #_
    (when (config/config :markdown-output-dir)
      (md/write-displayed-pages @last-bm (config/config :markdown-output-dir)))
    (log/info "Build stats:" (bd/stats @last-bm))
    #_ (dump bm)
    ;; Copy to
    (replace-directory output-dir (config/config :output-dir))
    bm
  ))

(defmulti produce-bm (fn [{:keys [source]}] (:type source)) )
  
;;; Sometimes I hate Clojure
(defmethod produce-bm :logseq [config]
  (logseq/produce-bm config))

(defmulti post-generation (fn [{:keys [source]} _] (:type source)))

(defmethod post-generation :logseq [_ _]
  (logseq/post-generation))

(defn do-post-generation
  [bm]
  (post-generation (config/config) bm))

;;; Debugging/curation related

(defn get-page
  [name]
  (bd/get-with-aliases @last-bm name))

(defn page-name
  [id]
  (:title (get-page id)))

(defn find-pages
  "Find pages whose name includes substring"
  [substring]
  (->> (bd/with-aliases @last-bm)
       keys
       (filter #(str/includes? % substring))))

;;; TODO find by content substring

(defn page-refs
  [name]
  (map page-name (bd/all-refs (get-page name))))

(defn gen-page
  [page]
  (u/memoize-reset!)
  (html-gen/generate-content-page @last-bm (config/config :output-dir)
                                  (bd/get-with-aliases @last-bm page)))

(defn gen-page-html
  [page]
  (u/memoize-reset!)
  (goddinpotty.rendering/page-hiccup page (bd/with-aliases @last-bm)))

(defn gen-block
  [block-id]
  (u/memoize-reset!)
  (goddinpotty.rendering/block-full-hiccup block-id @last-bm))

(defn gen-pages
  []
  (u/memoize-reset!)
  (html-gen/generate-goddinpotty @last-bm (config/config :output-dir))
  (post-generation (config/config) @last-bm))

(defn main
  [config-or-path]
  (if (map? config-or-path)
    (config/set-config-map! config-or-path)
    (config/set-config-path! (or config-or-path "default-config.edn")))
  (reset)
  (-> (config/config)
      produce-bm
      tap
      add-generated-pages
      tap
      output-bm
      do-post-generation)
  (log/info "Done")
  )

;;; HACK why don't I just get direct access to Datomic and make life easier?
;;; Assumes config is already set
;;; OK this was a really bad idea. To be workable, it would have
;;; to recompute :children
;;; Also doesn't even seem to work with a reindoex
(defn update-page
  [page-name]
    (u/memoize-reset!)
  (let [raw-blocks
        (logseq/get-page-blocks
         (get-in (config/config) [:source :graph])
         (:id (get-page page-name)))
        updated-bm (-> (reduce (fn [bm block]
                                 (when-not (contains? bm (:db/id block))
                                   (prn :new-block block))
                                 (assoc bm (:db/id block)
                                        (-> (get bm (:db/id block))
                                            (assoc :content (:block/content block))
                                            (goddinpotty.database/parse-block))))
                               @last-bm
                               raw-blocks)
                       goddinpotty.database/add-direct-children)]

    (html-gen/generate-content-page updated-bm (config/config :output-dir)
                                    (bd/get-with-aliases updated-bm page-name))))


(defn -main
  [& [config-or-path]]
  (main config-or-path)
  (when-not (= "repl" (:profile env/env))
    (System/exit 0)))


;;; Fails bad in compiled code
#_
(set! *print-length* 100)               ;prevent trace from blowing up trying to print bms. Should be elsewhere
