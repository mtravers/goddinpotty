(ns goddinpotty.templating
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [goddinpotty.rendering :as render]
            [goddinpotty.config :as config]
            [goddinpotty.utils :as utils]
            [goddinpotty.graph :as graph]
            [goddinpotty.search :as search]
            [goddinpotty.batadase :as bd]
            [goddinpotty.import.edit-times :as et]
            [goddinpotty.context :as context]
            [goddinpotty.twin-pages :as tp]
            [org.candelbio.multitool.core :as u]
            [hiccup2.core :as hiccup2]
            ))

;;; TODO Note: the functions of templating and html-gen seem to overlap; not sure they should be separate.

;;; Annoying amount of id/block switcheroo
(defn linked-reference-template
  [block-map r]
  (if (nil? (get block-map r))
    ""                                  ;TODO ugly
    (let [page (bd/block-page block-map (get block-map r))]
      [:div
       "from " (render/page-link page)
       ;; TODO this might want to do an expand thing like in recent-changes page? Does't actually seem necessary here
       ;; TODO has been assuming this is in a low-level block, but that's not necessarily the case. For [[Introduction to [[Inventive Minds]]]], it includes the whole page!
       ;; See bd/expand-to, but we want to shrink in this case
       [:div (render/block-full-hiccup r block-map)]])))

(defn linked-references-template
  [references block-map]
  (concat []
          (map (partial linked-reference-template block-map) references)))

(defn analytics-1
  []
  (and (config/config :google-analytics)
       [:script {:async true :src (format "https://www.googletagmanager.com/gtag/js?id=%s" (config/config :google-analytics))}]))

(defn analytics-2
  []
  (and (config/config :google-analytics)
       [:script
        (hiccup2/raw
         (format
          "
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());

  gtag('config', '%s');
" (config/config :google-analytics)))]))

(u/def-lazy comment-script
  (u/expand-template-string
   (slurp (io/resource "remarkbox.js"))
   ;; u/map-keys because expand-template-string is dumb
   (u/map-keys name (config/config))
   ))

(defn find-image
  [content]
  (-> (u/walk-find #(and (vector? %) (= (first %) :img)) content)
      second
      :src))

;;; TODO much of this should be configurable
(defn page-hiccup
  [contents title-text title-hiccup block-map & {:keys [head-extra widgets]}]
  (let [page-title (str (config/config :short-title) ": " title-text)
        page-url (str (config/config :real-base-url)
                      (utils/clean-page-title title-text)) ;not sure this is right
        image-url (find-image contents)
        description (or (config/config :description) title-text)
        ]
  `[:html
    [:head
     ~(analytics-1)
     ~(analytics-2)
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]

     ;; For Discord/FB previews. Stupid but that's the modern web
     [:meta {:name "og:type" :content "article"}]
     [:meta {:name "og:url" :content  ~page-url}]
     [:meta {:name "og:title" :content ~title-text}]
     [:meta {:name "og:description" :content ~description}]
     ~(when image-url
        `[:meta {:name "og:image" :content ~image-url}])

     [:title ~page-title]
     [:link {:rel "stylesheet"
             :href "https://cdn.jsdelivr.net/npm/bootstrap@5.1.1/dist/css/bootstrap.min.css" 
             :integrity "sha384-F3w7mX95PdgyTmZZMECAngseQB83DfGTowi0iMjiWaeVhAn4FJkqJByhZMI3AhiU"
             :crossorigin "anonymous"}]
     [:link {:rel "stylesheet" :href "assets/default.css"}]
     ~@(for [css (config/config :site-css)]
         `[:link {:rel "stylesheet" :href ~css}])
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com"}]
     ;; Using slightly-bold font for links for whatever reason.
     ;; TODO fonts should be specifiable in config
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=EB+Garamond:wght@400;550&display=swap"}]
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Varela+Round:wght@400;600&display=swap"}]
     ;; TODO this should be conditional on latex appearing on the page
     ;; see https://docs.mathjax.org/en/latest/web/typeset.html#load-for-math
     [:script {:src "https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml-full.js" :type "text/javascript"}]
     ~@(search/search-head)             ;search is on every page
     ~@head-extra
     ]
    [:body
     [:nav.navbar.navbar-expand-lg.navbar-dark.bg-dork.fixed-top
      [:div.container.navbar-x
       ~(render/page-link-by-name block-map (config/config :main-page) :class "navbar-brand")
       ;; TODO make active page machinery mork
       [:ul.navbar-nav.ml-auto
        ~@(for [page (config/config :right-navbar)]
            [:li.nav-item
             (if (vector? page) 
               (render/page-link (second page) :class "nav-link" :bm block-map :alias (first page))
               (render/page-link page :class "nav-link" :bm block-map))]
            )]]]
     [:div.container.main
      [:div.row
       #_ "<!-- Sidebar Widgets Column -->"
       [:div.col-md-3.sidebar

        ~@widgets
        ]

       #_ "<!-- Post Con<tent Column -->"
       [:div.col-lg-7
        #_ "<!-- Title -->"
        [:div.ptitle
         [:h1 ~title-hiccup]
         ~contents
         ]
        ;; Comments – resuing card machinery, probably not a great idea TODO
        ~(when (config/config :comments)
           `[:div.card
             [:h5.card-header {:style "margin-bottom:-12px;"} "Comments"]
             [:div.card-body
              [:div#remarkbox-div
               [:noscript
                [:iframe#remarkbox-iframe {:src "https://my.remarkbox.com/embed?nojs=true"
                                           :style "height:600px;width:100%;border:none!important"
                                           :tabindex 0}]]]]])
        ]]]
     #_ "<!-- Footer -->"
     [:footer.py-5.footer
      [:div.container
       ~(when (config/config :colophon)
          `[:p.m-0.text-center.text-white ~@(config/config :colophon)])
       ;; Out of service because it adds a gratuitoous change to all files, not good with git.
       ;; TODO maybe a server-side include or frame or something. Or a config option
       #_
       [:p.m-0.text-center.text-white.small "Exported " ~(utils/render-time (utils/latest-export-time))]]
      ]
     [:script {:src "https://cdn.jsdelivr.net/npm/bootstrap@5.1.1/dist/js/bootstrap.bundle.min.js"
               :integrity "sha384-/bQdsTh/da6pkI1MST/rWKFNjaCP5gBSY4sEBT38Q/9RBh9AH40zEOg7Hlq2THRZ"
               :crossorigin "anonymous"}]
     ;; Comment box
     ~@(when (config/config :comments)
         `[[:script {:src "https://my.remarkbox.com/static/js/iframe-resizer/iframeResizer.min.js"}]
           [:script ~(deref comment-script)]])
     ]]))

(defn map-page
  [bm]
  (page-hiccup
   (graph/render-graph
    bm
    (config/config :output-dir)
    {:name "fullmap" ;warning: this name can't be the same as a page name!
     :include-all? (config/config :unexclude?)
     })
   "Map"
   "Map"
   bm
   :head-extra (graph/vega-head)))

(defn render-date-range
  [[from to]]
  [:div.date (utils/render-date from) " - " (utils/render-date to)])

(defn render-page-hierarchy-1
  [path page-struct bm this]
  (let [top (clojure.string/join "/" path)
        page (bd/get-with-aliases bm top)]
    (when (and page (bd/displayed? page))
      [:div
       [:li (render/page-link top :bm bm :alias (last path))]
       ;; TODO tweak css so long things look rightish
       ;; whitespace: nowrap (but needs to truncate or something)
       (when (map? page-struct)
         [:ul
          (for [child (u/sort-with-numeric-prefix (keys page-struct))] ;Sort imposes some order, better than random I guess
            (render-page-hierarchy-1 (conj path child)
                                     (get page-struct child)
                                     bm this)
            )])])))


(defn render-page-hierarchy
  [page-name bm]
  (context/with-context [:page page-name]
    (let [[_ top] (or (re-find #"^(.*?)/(.*)$" page-name) [nil page-name])
          page-struct (get (bd/page-hierarchies bm) top)]
      (render-page-hierarchy-1 [top] page-struct bm page-name))))

;;; Note: these links have a different semnatics than normal ones, and so maybe should be styled differently
(defn render-toc
  [toc bm]
  [:ul.ulz
   (for [[indent id] toc]
     [:li 
      [:span {:style (utils/css-style {:width (* 15 (-  indent 1))})}]     ;ech
      [:a {:href (str "#" id)} (render/block-local-text bm (get bm id))]]
     )])

;; TODO this page should be hidden probably
(def about-block-title "AboutBlock")    ;TODO config option

(defn about-content
  [bm]
  (when-let [block (bd/get-with-aliases bm about-block-title)]
   (render/block-full-hiccup about-block-title bm)))

(defn about-widget
  [bm]
  (when-let [about-content (about-content bm)]
          [:div.card.my-3
           [:h5.card-header "About"]
           [:div.card-body.minicard-body
            about-content]]))

;;; TODO move to search.clj
(defn search-widget
  [bm#]
  [:div.card.my-3
   [:h5.card-header
    [:span#searchh
     "Search"]
    [:input#searcht.form-control {:type "text"
                                  ;; :placeholder "Search for..."
                                  :onkeyup "keypress(event)"
                                  }]]
   [:div#searchb.card-body
    ;; output goes here
    [:div#searcho] 
    ]])


;;; Light weight and incomplete cal widget, only of use when showing journal pages

(defn journal-page-inc
  [bm page inc]
  ;; TODO filter to existing pages, eg increment until one os hit
  (utils/clean-page-title (utils/inc-page (:title page) inc)))

(defn calendar-widget
  [bm page]
  (let [today (utils/parse-journal-page-name (:title page))]
    [:div.card.my-3
     [:h5.card-header
      [:span#searchh
       "Calendar"]
      ]
     [:divcard-body
      [:span.btn-group
       [:a.btn {:href (journal-page-inc bm page -1)} (render/icon "chevron-left")]
       ;; Note: this doesn't work yet – not clear how it could without recapitulating a lot of date parse logic
       [:input {:type "date"
                :name "date"
                :value (.format utils/html-date-formatter today)
                :onchange "var d = event.target.value; window.location.href = d;"
                }]
       [:a.btn {:href (journal-page-inc bm page 1)} (render/icon "chevron-right")]]
      ]]))

(defn logseq-url
  [page]
  (format "logseq://graph/%s?page=%s"
          (config/config :source :graph)
          (java.net.URLEncoder/encode (:title page))))

(defn block-page-hiccup
  [block-id block-map output-dir]
  (let [block (get block-map block-id)
        title-hiccup (render/block-content->hiccup (:title block) block-map)
        title-text (:title block)
        contents
        [:div
         [:div
          (u/ignore-errors
            (render-date-range
             #_ (bd/date-range block)
             (et/page-date-range block)  ;TODO LOGSEQ specific (file-based), should be configurable
             ))]
         (when-not (:include? block)
           [:span [:b "EXCLUDED"]])
         [:span.local                   ;Hidden unless running locally.
          " "
          [:a {:href (logseq-url block)} "Open in Logseq"]]
         [:hr {}]
         (render/page-hiccup block-id block-map)
         [:hr {}]]

        search-widget (search-widget block-map)
        about-widget (about-widget block-map)
        calendar-widget (when (bd/daily-notes-page? block)
                          (calendar-widget block-map block))
        map-widget
        [:div.card.my-3
         [:h5.card-header
          [:a {:data-bs-toggle "collapse"
               :data-bs-target "#mapgraph"
                                        ;               :type "button"
               :aria-expanded "false"
               :aria-controls "mapgraph"
               :onclick "toggleMap();"  ;hack to persist state, see search.js
               }
           "Map"]
          [:span {:style (utils/css-style
                          {:float "right"
                           :display "flex"
                           })}
           (render/page-link-by-name block-map "Map" :alias "Full" )]]
         [:div#mapgraph.collapse
          {:class "show"};; Default to open, remove if default closed (TODO make configurable)
          [:div.card-body {:style (utils/css-style {:padding "2px"})}
           ;; TODO possible config to do embedded vs external
           (graph/render-graph ;; render-graph-embedded
            block-map
            output-dir
            {:name (utils/clean-page-title title-text)
             :width 290                  ;This depends on the column width css, currently my-3
             :height 350
             :controls? false
             :link-distance 65
             :node-charge -60
             :node-radius 25
             :center block-id
             :radius 2}) ;Sadly 1 is too small and 2 is too big. Need 1.1
           ]]]

        incoming-links-widget           ;TODO suppress if #incoming on page
        (let [linked-refs (bd/get-displayed-linked-references block-id block-map)]
          (when-not (empty? linked-refs)
            [:div.card.my-3
             [:h5.card-header "Incoming links"]
             [:div.card-body.gp-card-body
              [:div.incoming
               (linked-references-template linked-refs block-map)]]]))

        ;; TODO well the fact that I have these two similar-yet-different things is a sign that this whole page-based hypertext thing is a crock. Not sure what to do about it though.

        page-hierarchy-widget
        (when (bd/page-in-hierarchy? block block-map)
          [:div.card.my-3
           [:h5.card-header "Page Tree"] ;was Contents but now we have intrapage contents...
           [:div.card-body.gp-card-body
            (render-page-hierarchy (:title block) block-map)]])

        ;; See http://webseitz.fluxent.com/wiki/TwinPages 
        twin-pages-widget
        (when (config/config :twin-pages?)
          (when-let [content (tp/twin-pages-widget-static title-text)]
            [:div.card.my-3
             ;; Note: this is a link to AMMDI, may or may not be right for other uses
             [:h5.card-header [:a {:href "http://hyperphor.com/ammdi/Twin-Pages"} "Twin Pages"]]
             [:div.card-body
              content
              ]]))

        page-contents-widget
        (let [toc (bd/toc block)]
          (when (> (count toc) 2)       ;TODO maybe make this configurable
            [:div.card.my-3
             [:h5.card-header "Page Contents"]
             [:div.card-body.gp-card-body
              (render-toc toc block-map)
              ]]))
        ]

    (page-hiccup contents title-text title-hiccup block-map
                 :head-extra (graph/vega-lite-head) ;lite to support new dataviz
                 ;; TODO wants to be configurable
                 ;; TODO also all widgets should be collapsible and the state remembered (map does this, but they all should)
                 :widgets [about-widget ;eg don't need this in my personal versioN!
                           search-widget
                           calendar-widget
                           page-contents-widget ;TODO only render when needed
                           map-widget
                           incoming-links-widget
                           page-hierarchy-widget
                           twin-pages-widget])
    ))


;;; TODO this also should suppress the incoming links in lh column
;;; Oh shit this can't work, rendering happens too early 
;;; Needs to be a post-processing step, fuck me
;;; OR maybe not if I use a delay...what a hack. No that can't really work I think...delays require an explict deref
;;; Not really working yet so disabled
#_
(bd/register-special-tag
 "incoming"
 (fn [bm block]
   (fn [bm]                             ;return a fn to be rendered later with the full bm
     (linked-references-template
      (bd/get-displayed-linked-references (:id (bd/block-page bm block)) bm)
      bm))))
