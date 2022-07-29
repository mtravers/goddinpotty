(ns goddinpotty.convert.roam-logseq
  (:require [goddinpotty.export.markdown :as md]
            [goddinpotty.import.roam :as roam]
            [goddinpotty.import.roam-images :as roam-images]
            [goddinpotty.batadase :as bd]
            [me.raynes.fs :as fs]
            [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            )
  (:gen-class))

;;; Toplevel for Roam → Logseq converter

;;; TODO painfully slow for some reason, try to speedup if releasing as a standalone tool

(def last-bm (atom {}))

;;; TODO Warning does a very hard reset, probably a better idea to just clear out the three subdirs, creating if necessary
(defn- reset-directory
  [dir]
  (fs/delete-dir dir)
  (fs/mkdir dir)
  (doseq [d ["assets" "pages" "journals"]]
    (fs/mkdir (str dir "/" d))))

(defn write-page
  [bm page directory & [name]]
  (let [name (or name (:content page))
        file-name (md/md-file-name name)
        place (if (bd/daily-notes-page? page) "journals" "pages")
        path (str place "/" file-name)
        full-path (str directory "/" path)]
    (if (fs/exists? full-path)
      (do
        (prn :case-folding-collision full-path)
        (write-page bm page directory (str name "+")))
      (do
        (prn :writing path)
        (md/write-page bm page full-path)))))

(defn write-pages
  [bm directory]
  (doseq [page (bd/pages bm)]
    (write-page bm page directory)))

(defn maybe-unzip
  [f]
  (case (fs/extension f)
    ".zip" (roam/unzip-roam f)
    ".edn" f
    (throw (ex-info "File has unknown extension" {:file f}))))

(defn do-it
  [edn-file output-dir]
  (reset-directory output-dir)
  (let [bm (-> edn-file
               maybe-unzip
               roam/roam-db-edn)
        images (roam-images/download-images bm output-dir) ; []
        xbm (roam-images/subst-images bm images)
        ]
    (prn (assoc (bd/stats xbm) :downloaded-images (count images)))
    (reset! last-bm xbm)
    (write-pages xbm output-dir)
    bm))

;;; TODO should probably accept zips, that what you get from Roam
;;; TODO option to skip images or reuse them, no need to redo it each time.
(defn -main
  [edn-file output-dir]
  (do-it edn-file output-dir)
  (System/exit 0))

;;; TODO link in
#_
(defn convert-twitter-links
  [l]
  (let [link (re-find #"http.*twitter.com/\S*" l)]
    (when (and link
               (not (re-find #"\{\{tweet" l)))
      (str/replace l  #"http.*twitter.com/\S*" (format "{{tweet %s}}" link)))))
