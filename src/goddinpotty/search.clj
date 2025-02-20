(ns goddinpotty.search
  (:require [org.candelbio.multitool.core :as u]
            [goddinpotty.batadase :as bd]
            [goddinpotty.rendering :as render]
            [goddinpotty.utils :as utils]
            [clojure.string :as str]
            [goddinpotty.import.edit-times :as et]
            ))

;;; Writes out a JSON search index for Elasticlunr
;;; See resources/public/search.js for the code that consumes it

(defn index
  [bm]
  (u/for* [page (bd/displayed-pages bm)
           index (range)]
    (u/clean-map          
     {:id index                          ;TODO not sure this is necessary, we don't use it
      :url (utils/clean-page-title (:title page))
      ;; If punctuation is causing problems, try fiddling with elasticlunr.tokenizer.seperator
      :title (:title page)
      :alias (when-let [aliases (:alias page)]
               (str/join " " aliases))
      :body (render/block-full-text bm page)
      ;; Adding these fields for index pages
      :date (et/page-edit-time page)    ;TODO format prettier
      :depth (:depth page)
      :size (bd/size page)
      })))

(defn write-index
  [bm output-dir]
  (utils/write-json (str output-dir "/assets/index.js")
                    (index bm)))

(defn search-head
  []
  [[:script {:src "assets/elasticlunr.min.js"}] ; From https://elasticlunr.com/elasticlunr.min.js
   [:script {:src "assets/search.js"}]])
