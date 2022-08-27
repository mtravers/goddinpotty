(ns goddinpotty.convert.roam-logseq
  (:require [goddinpotty.export.markdown :as md]
            [goddinpotty.import.roam :as roam]
            [goddinpotty.import.roam-images :as roam-images]
            [goddinpotty.batadase :as bd]
            [goddinpotty.config :as config]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log]
            )
  (:gen-class))

;;; Toplevel for Roam → Logseq converter

;;; TODO painfully slow for some reason, try to speedup if releasing as a standalone tool (could cut out all the parsing)
;;; TODO Roam uses __italics__, which Logseq renders as bold, preferring *italics*. Should do a translation but that means reaching into the parse more than I'm doing now
;;; TODO

(def last-bm (atom {}))

;;; This semi-clears the output directory. Assets files are left there to avoid downloading them multiple times.
;;; Or preserve the assets directory so downloads don't need to be redone
(defn- reset-directory
  [dir]
  (fs/mkdir dir)
  (doseq [d ["pages" "journals"]]
    (fs/delete-dir (str dir "/" d)))
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
        (log/warn "Case folding collision" full-path)
        (write-page bm page directory (str name "+")))
      (do
        (log/info :writing path)
        (md/write-page bm page full-path)))))

(defn write-pages
  [bm directory]
  (doseq [page (bd/pages bm)]
    (try
      (write-page bm page directory)
      (catch Throwable e
        (log/error "Error writing" (:content page) e)))))

(defn maybe-unzip
  [f]
  (case (fs/extension f)
    ".zip" (roam/unzip-roam f)
    ".edn" f
    (throw (ex-info "File has unknown extension" {:file f}))))

(defn do-it
  [edn-file output-dir]
  (log/info "Starting conversion")
  (config/set-config-map! {:daily-notes? true
                           :exit-tags []
                           :unexclude? true
                           :output-dir output-dir})
  (reset-directory output-dir)
  (let [bm (-> edn-file
               maybe-unzip
               roam/roam-db-edn)        ;TODO there is no need to do most of the work this does, particularly parsing 
        _ (reset! last-bm bm)
        images (roam-images/download-images bm output-dir) ; []
        xbm (roam-images/subst-images bm images)
        ]
    (log/info "Stats" (assoc (bd/stats xbm) :downloaded-images (count images)))
    (reset! last-bm xbm)
    (write-pages xbm output-dir)
    bm))

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


;;; TODO this python script does a few more useful things:
;;; https://github.com/sebastianpech/roamtologseq
