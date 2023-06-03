(ns goddinpotty.curation
    (:require [goddinpotty.config :as config]
              [goddinpotty.utils :as utils]
              [goddinpotty.batadase :as bd]
              [goddinpotty.core :as core]
              [org.candelbio.multitool.core :as u]
              [org.candelbio.multitool.nlp :as nlp]
              [org.candelbio.multitool.cljcore :as ju]
              [me.raynes.fs :as fs]
              [clojure.string :as str]
              [clj-http.client :as client]
              ))

;;; Curation functions. For use via REPL; not wired into main program.
;;; A lot of code here is for one-off operations and is not particularly high-quality.

;;; Prettifying naked urls: 
;;; See https://github.com/mtravers/voracious/blob/master/src/voracious/projects/clothe_naked_urls.clj


;;; Convenience
(defn bm
  []
  @core/last-bm)

(defn block-links
  [block]
  (u/walk-collect #(and (string? %)
                        (str/starts-with? % "http")
                        %)
                  (:parsed block)))

(defn check-link
  [url]
  (client/head url
               {:cookie-policy :standard
                :trace-redirects true
                :redirect-strategy :graceful}))

(defn check-external-links
  "Find bad external links in the background."
  [bm]
  (let [bads (atom [])
        goods (atom [])]
    (doseq [block (vals bm)
            link (block-links block)]
      (future-call
       #(try
          (prn :check-link link)
          (swap! goods conj [link (check-link link)])
          (catch Throwable e (swap! bads conj [link block (:title (bd/block-page block)) e])))))
    [bads goods]))



;;; OK...next step is to generate archive.org links where possible, andd substitute them..

;;; –
(defn list-dir-recursive
  [dir]
  (mapcat (fn [f] (if (fs/directory? f)
                     (list-dir-recursive f)
                     (list f)))
           (fs/list-dir dir)))

;;; Check output and ensure all local html links are valid.
;;; This finds a lot of missing things due to Zoetero import, plus
; :missing "Topics.html" :in "play.html"
; :missing "Topics.html" :in "agency.html"
; :missing "Mastery-of-Non-Mastery.html" :in "mimesis.html"
(defn check-output-links
  []
  (doseq [f (list-dir-recursive (:output-dir (config/config)))]
    (doseq [link (map second (re-seq #"href=\"(.*?)\"" (slurp f)))]
      (if (str/starts-with? link "http")
        nil                             ;ignore external links
        (when-not (fs/exists? (str "output/" link))
          (prn :missing link :in (.getName f)))))))


(defn wayback
  [url]
  (let [resp (client/get "http://archive.org/wayback/available"
                         {:query-params {:url url}})]
    (clojure.data.json/read-str (:body resp) :keyword-fn keyword)))


;;; Highly connected nodes
(defn fan
  [bm]
  (let [bm (u/map-values #(assoc % :fan (count (bd/page-refs bm %))) bm)]
    (map (juxt :content :fan) (take 50 (reverse (sort-by :fan (filter :fan (vals bm))))))))

;;; Find blocks containing a given string.
;;; To be usefufl, probably wants to return pages
; (goddinpotty.batadase/block-page @last-bm (get @last-bm dblock))
(defn grep
  [bm string]
  (filter #(re-find (re-pattern string) (or (:content %) "")) (vals bm)))

(defn roam-image?
  "Returns the extension if this is in fact a roam image, nil otherwise"
  [url]
  (second (re-matches #"https\:\/\/firebasestorage\.googleapis\.com/.*\.(\w+)\?.*" url)))

(defn download-images
  [bm directory]
  (doseq [image-block (filter #(= :image (first (second (:parsed %)))) (vals bm))]
    (let [markdown (second (second (:parsed image-block)))
          ;; See rendering/format-image
          image-source (utils/remove-n-surrounding-delimiters 1 (re-find #"\(.*?\)" markdown))]
      (when-let [ext (roam-image? image-source)]
        ;; TODO has failure modes if page name contains / ! and maybe other chars. 
        (let [local-file (str directory (:title (bd/block-page bm image-block)) "-" (:id image-block) "." ext)]
          (prn :download local-file image-source)
          (ju/local-file image-source local-file))))))

;;; Reexport from logseq

#_
(def logseq (utils/read-json "/Users/mtravers/Downloads/System_Volumes_Data_misc_working_org-roam_roam_1633487329.json"))


;;; Split



;; Idea:
;;- daily notes, plus anything heavily linked to (like #bikeride or other habits) gos in one bucket
;; - published pages
;; - everything else

;;Maybe too fancy, how about just separate out daily notes and everything else?



#_
(defn file-subst
  [f substs prefix]
  (->> f
       ju/file-lines
       doall
       (map (partial subst-images substs prefix))
       (ju/file-lines-out f)))

#_
(file-subst "/misc/repos/ammdi-augmented/pages/Whole Earth Catalog.md" substs "../assets/")

#_
(doseq [f (fs/list-dir "/misc/repos/ammdi-augmented/pages/")]
  (prn f)
  (file-subst f substs "../assets/"))


;;; Some general tooling for doing transforms. Note this might want to get packaged up into an
;;; import utility if I ever finish that.
;;; Note: doesn't find files in nested directories. 
(defn all-content-pages
  []
  (concat (fs/list-dir "/opt/mt/repos/ammdi/journals/")
          (fs/list-dir "/opt/mt/repos/ammdi/pages")))


(defn convert-twitter-links
  [l]
  (let [link (re-find #"http.*twitter.com/\S*" l)]
    (when (and link
               (not (re-find #"\{\{tweet" l)))
      (str/replace l  #"http.*twitter.com/\S*" (format "{{tweet %s}}" link)))))


(defn process-files
  [line-function]
  (doseq [f (all-content-pages)]
    (ju/process-file-lines f line-function)))

;;; Run once.
#_
(process-files convert-twitter-links)
  
  
;;; Roam seems to add a lot of NON-BREAKING SPACE chars, this converts them to vanilla spaces
#_
(process-files (fn [l] (str/replace l #" " " ")))


;;; TODO doesn't work for vimeo links, should check (Logseq has vimeo embed but seems broken)
#_
(process-files (fn [l] (str/replace l #"\{\{video" "{{youtube")))


;;; Convert Roam: {{alias [[AI risk ≡ capitalism]]capitalism}}.
;;; To logseq [capitalism]([[AI risk ≡ capitalism]])
;;; Have only a few, do doing by hand, but here for the record


;;; This is to deal with a Logseq bug in which almost all of a pages content vanishes.
;;; This finds tiny files in the output
;;; Note to me: M-x magit-log-buffer-file to see git history for a file
(defn find-disappeared-pages
  []
  (doseq [f (all-content-pages)]
    (when (< (fs/size f) 5)
      (prn (str f) (fs/size f) (java.util.Date. (fs/mod-time f))))))


(defn convert-twitter-links
  [l]
  (let [link (re-find #"http.*twitter.com/\S*" l)]
    (when (and link
               (not (re-find #"\{\{tweet" l)))
      (str/replace l  #"http.*twitter.com/\S*" (format "{{tweet %s}}" link)))))

;;; Note: this does NOT detect collisions between aliases and real file names, that will have to be done elsewhere, like during import.
(defn detect-case-fold-problems
  "Detects when there are distinct pages whose names are identical under case-folding"
  [bm]
  (let [bm (bd/with-aliases bm)]
    (filter #(> (count (last %)) 1)
            (map
             (fn [group]
               (conj group (distinct (map (fn [alias] (get-in bm [alias :id])) group))))
             (filter #(> (count %) 1)
                     (vals (group-by str/lower-case (keys bm))))))))

(defn delete-empty-files
  [dir]
  (doseq [f (fs/list-dir dir)]
    (when (= 0 (fs/size f))
      (prn :deleting f)
      (fs/delete f))))

;;; Word counts
(defn block-word-count
  [bm block]
  (->> block
      (goddinpotty.rendering/block-local-text bm)
      nlp/tokens
      count))


(def +* (u/vectorize +))

;;; Way slow, it re-renders each page
;;; TODO devops to run and store this on an ongoing basis
(defn global-word-count
  [bm]
  (loop [displayed-words 0
         private-words 0
         [block & rest] (vals bm)]
    (if (nil? block)
      [displayed-words private-words]
      (if (bd/displayed? block)
        (recur (+ displayed-words (block-word-count bm block))
               private-words
               rest)
        (recur displayed-words
               (+ private-words (block-word-count bm block))
               rest)))))

;;; [251250 630248] Oct 30 2022
;;; TODO run this daily (and in the past with git) and make a graph



;;; Converting old sidenotes to new
(def sidenote-report
  (map (fn [id]
         (let [block  (get bm id) 
               page (bd/block-page bm block)]
           {:id id :content (:content block) :page (:title page)}))
       @sidenotes))


;; NLP!

(defn block-freq
  [bm block]
  (->> block
       (goddinpotty.rendering/block-local-text bm)
       nlp/tokens
       nlp/remove-ruthlessly
       frequencies))

(defn full-freq
  [bm]
  ;; Probanly a dumb way to compute this
  (reduce (fn [a b] (merge-with + a b))
          (map (partial block-freq bm) (vals bm))))

  
;;; Images
(defn all-images
  [bm]
  (for [image-block (filter #(= :image (first (second (:parsed %)))) (vals bm))]
    (let [markdown (second (second (:parsed image-block)))
          ;; See rendering/format-image
          image-source (utils/remove-n-surrounding-delimiters 1 (re-find  #"\(.*?\)" markdown))]
      image-source)))

(defn repo-images
  [bm]
  (->> bm
       all-images
       (map #(second (re-matches #"\.\./assets/(.*)" %)))
       (filter identity)))

(defn assets
  []
  (map fs/base-name  (fs/list-dir "/opt/mt/repos/ammdi/assets")))

(defn unused-images
  []
  (clojure.set/difference
   (set (assets))
   (set (repo-images @goddinpotty.core/last-bm))))

;;: TODO for remote-hosted images, copy them locally and update links

;;; Converting old sidenotes
;;; find block-refs to blocks on same page (repurpose old code)


;;; Block has a :block-ref, this determines if it is a sidenote or not
(defn block-block-refs
  [block]
  (->> block
       :parsed
       (u/walk-collect #(and (sequential? %)
                             (= :block-ref (first %))
                             (second %)
                             ))
       (map utils/remove-double-delimiters)
       ))

(defn block-has-sidenote?
  [block]
  (some (fn [ref]
          (some (fn [child]
                  (= ref (get-in child [:properties :id])))
                (:dchildren block)))
        (block-block-refs block)))

(defn sidenote-pages
  [bm]
  (let [sidenote-blocks
        (->> bm
             vals
             (filter block-has-sidenote?))
        sidenote-pages
        (distinct (map (partial bd/block-page bm) sidenote-blocks))]
    (prn [(count sidenote-blocks) :sidenotes (count sidenote-pages) :pages])
    (map :title sidenote-pages)))

;;; Not that many
#_
("__Materialism__, Terry Eagleton"
 "Media Science"
 "Patterns of Refactored Agency"
 "Introduction to __Inventive Minds__"
 "LWMap/What Motivated Rescuers During the Holocaust?"
 "Demeney Voting"
 "nihilism"
 "Buddhism"
 "nebulosity"
 "Urizen"
 "Nihilism and Agency"
 "Notes on __Daybreak__"
 "LWMap/Meta-honesty"
 "Vimalakirti Sutra"
 "Goddinpotty"
 "Mastery of Non-Mastery in the Age of Meltdown"
 "Labor and Embodiment"
 "__On Purpose__")
    
 ;;; Alright easier to just hand edit.
