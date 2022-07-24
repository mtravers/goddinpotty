(ns goddinpotty.curation
    (:require [goddinpotty.config :as config]
              [goddinpotty.utils :as utils]
              [goddinpotty.database :as database]
              [goddinpotty.batadase :as bd]
              [goddinpotty.core :as core]
              [clojure.set :as set]
              [org.parkerici.multitool.core :as u]
              [org.parkerici.multitool.cljcore :as ju]
              [me.raynes.fs :as fs]
              [clojure.string :as str]
              [clj-http.client :as client]
              ))

;;; Curation functions. For use via REPL; not wired into main program.
;;; A lot of code here is for one-off operations and is not particularly high-quality.

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

;;; TODO convert bare URLs into [label](url)


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


(defn page-images
  [bm]
  (u/collecting
   (fn [collect]
     (u/map-values
      (fn [block]
        (when-let [match (and (string? (:content block))
                              (re-matches #"^!\[\]\((.*)\)" (:content block)))]
          (collect [(:id block) (:id (bd/block-page bm block)) (second match)])))
      bm))))

(defn image-copy-script
  [bm dir]
  (doseq [[id page url] (page-images bm)]
    (let [file (format "%s/%s-%s.png" dir page id)]
      (println (format "curl \"%s\" > \"%s\"" url file)))))

;;; TODO Pici images

;;; Reexport from logseq

#_
(def logseq (utils/read-json "/Users/mtravers/Downloads/System_Volumes_Data_misc_working_org-roam_roam_1633487329.json"))


;;; Split



;; Idea:
;;- daily notes, plus anything heavily linked to (like #bikeride or other habits) gos in one bucket
;; - published pages
;; - everything else

;;Maybe too fancy, how about just separate out daily notes and everything else?

(defn subst-images
  [substs prefix s]
  (let [[m u] (re-find #"\((https://firebasestorage.*)\)" s)]
    (if m
      (if (contains? substs u)
        (str/replace s u (str prefix (get substs u)))
        (do
          ;; TODO this is bad, it ends up in output file because Clojure can be dumb
          (prn :not-found u s)          ;shouldn't happen
          s))
      s)))

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
(defn detect-case-fucks
  "Detects when there are distinct pages whose names are identical under case-folding"
  [bm]
  (let [bm (bd/with-aliases bm)]
    (filter #(> (count (last %)) 1)
            (map
             (fn [group]
               (conj group (distinct (map (fn [alias] (get-in bm [alias :id])) group))))
             (filter #(> (count %) 1)
                     (vals (group-by str/lower-case (keys bm))))))))

;;; As of 23 July 2022
#_
(["gnostics" "Gnostics" (21537 23988)]
 ["Boyz" "boyz" (4596 6441)]
 ["SuperEgo" "Superego" (27470 8555)]
 ["Nuna" "nuna" (164 12085)]
 [16 "16" (16 37441)]
 ["BwO" "bwo" (34338 6779)]
 ["Gnostic" "gnostic" (23382 21537)])



   
