(ns goddinpotty.twin-pages
  (:require [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.cljcore :as ju]
            [hiccup2.core :as hiccup2]
            [clojure.string :as str]
            [goddinpotty.endure :as e]
            )
  )

;;; See http://webseitz.fluxent.com/wiki/TwinPages

;;; The standard Twin Pages widget. Will not work in https: sites
(defn twin-pages-widget-dynamic
  []
  [:div#twin_pages
   {:style {}} ;"border:1px; vertical-align:top; horizontal-align:center"
   [:script {:src "http://www.wikigraph.net/twinpages.js"
             :referrerpolicy "unsafe-url"
             }]])



;;; Doc extraction
(def doc-extract-re #"(?s).*document\.write\(\'(.*)\'\).*")

;;; Hack: generate it at compile time, why not?
;;; Might be putting undue load on their server, maybe should cache locally and only refresh ocassionaly
;;; TODO parse out urls and reformat and add _target etc, the default's a bit out of step with my design
(e/defn-memoized twin-pages-content
  [title]
  (u/ignore-report
    (->> (u/expand-template
          "curl -e https://hyperphor.com/ammdi/{{title}} \"http://www.wikigraph.net/twinpages.js\""
          {:title (str/replace title " " "_")})
         ju/sh-str
         (re-matches doc-extract-re)
         second
         )))


(defn twin-pages-widget-static
  [title]
  (when-let [content (twin-pages-content title)]
    (if (re-find #"No twinpages" content)
      nil
      (hiccup2/raw content))))

;;; ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ ⪡⦿⪢ 

;;; For GardenClub, not to be confused with goddinpotty
(def html-link-re
  #"<a href=\"(.*?)\">(.*?)</a>")

;;; → Multitool 
(defn third
  [s]
  (nth s 2))

(defn extract
  []
  (->> @(e/memoizer 'twin-pages-content)
       (filter (fn [[k v]] (and v  (re-find #"TwinPages\:" v))))
       (into {})
       (u/map-values #(let [m (re-seq html-link-re %)]
                        (zipmap (map third m) (map second m))))
       (u/map-keys first)))

)
