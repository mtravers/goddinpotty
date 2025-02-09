(ns goddinpotty.index
  (:require [goddinpotty.utils :as utils]
            [goddinpotty.batadase :as bd]
            [goddinpotty.import.edit-times :as et]
            [goddinpotty.templating :as templating]
            [goddinpotty.rendering :as render]
            [goddinpotty.config :as config]
            [org.candelbio.multitool.core :as u]
            [clojure.string :as s]))

;;; Wanted to switch to ag-grid but would be easier with an app

;;; TODO # of refs (incoming/outgoing/both)

(def indexes
  [{:name "Title"
    :sort-key (comp u/numeric-prefix-sort-key s/upper-case :title)
    :render render/page-link
    :page-title "Index"                 ;kludge to match block-map and links
    :col-width "65%"
    }
   {:name "Date"
    :filter-key et/page-edit-time
    :sort-key (comp - inst-ms et/page-edit-time)
    :render (comp utils/render-time et/page-edit-time)}
   {:name "Depth"
    :sort-key :depth
    :render :depth}
   {:name "Size"
    :sort-key (comp - bd/size)
    :render #(format "%.1fK" (double (/ (bd/size %) 1000)))}
   ])

;;; Copied with mods from html-gen due to namespace fuck
(defn generated-page
  "Add a generated page to the block map"
  [name generator]
  {:id name
   :title name
   :special? true                ;I miss OOP
   :generator generator
   :include? true
   :display? true
   :page? true
   })

;;; Returns map to merge into bm
(defn make-index-pages
  [bm]
  (let [pages (remove :special? (bd/displayed-regular-pages bm))
        page-id (fn [name] (format "Index-%s" name))
        ]
    {"Index"
     (generated-page
      "Index"
      (fn [bm]
        (let [hiccup
              [:div#myGrid {:style {:height "800px"}}]
              title "Index"]
          (templating/page-hiccup
           hiccup title title bm
                   :head-extra [[:script {:src "https://cdn.jsdelivr.net/npm/ag-grid-community/dist/ag-grid-community.min.js"}]
                                [:script {:src "assets/grid.js"}]]
                   :widgets [(templating/about-widget bm)
                             (templating/search-widget bm)])
          )))}))


;;; Dev only
(defn write-index-page
  [bm]
  (goddinpotty.html-generation/export-page
   ((:generator (first (vals (make-index-pages @goddinpotty.core/last-bm))))
    bm)
   "/Index"
   (config/config :output-dir)))


