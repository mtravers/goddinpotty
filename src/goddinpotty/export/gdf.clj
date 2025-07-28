(ns goddinpotty.export.gdf
  (:require [goddinpotty.batadase :as bd]
            [goddinpotty.utils :as utils]
            [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.cljcore :as ju]
            ))

;;; For Gephi
;;; Minimal for now, could export other properties
;;; Gephi is just another hairball generator (more layout algos and parameters) so Not taht exciting

(def node-def "nodedef> name VARCHAR,label VARCHAR")
(def edge-def "edgedef> node1 VARCHAR,node2 VARCHAR")

(u/defn-memoized clean
  [t]
  (utils/clean-page-title t))

(defn export-gdf
  [bm file]
  (ju/file-lines-out
   file
   (concat (list node-def)
           (map #(format "%s,%s" (clean (:title %)) (clean (:title %))) (bd/pages bm))
           (list edge-def)
           (mapcat (fn [page]
                     (map (fn [ref]
                            (format "%s,%s" (clean (:title page)) (clean ref)))
                          (bd/page-refs bm page)))
                   (bd/pages bm)))))
               
  
