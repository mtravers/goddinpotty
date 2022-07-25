(ns goddinpotty.export.roam-logseq
  (:require [goddinpotty.export.markdown :as md]
            [goddinpotty.import.edn :as roam]
            [goddinpotty.import.roam-images :as roam-images]
            [goddinpotty.batadase :as bd]
            [me.raynes.fs :as fs]
            [org.parkerici.multitool.cljcore :as ju]
            ))

#_
(utils/unzip-roam "/opt/mt/working/roam-takeout/mt-pici/Roam-Export-1636387068791.zip")

;;; TODO painfully slow for some reason, try to speedup if releasing as a standalone tool
;;; TODO generating a shit-ton of zero-length files, should omit 
;;; TODO image asseets, that was the whole freaking point.

(def last-bm (atom {}))

;;; TODO Warning does a very hard reset, probably a better idea to just clear out the three subdirs, creating if necessary
(defn- reset-directory
  [dir]
  (fs/delete-dir dir)
  (fs/mkdir dir)
  (doseq [d ["assets" "pages" "journals"]]
    (fs/mkdir (str dir "/" d))))

(defn write-pages
  [bm directory]
  (doseq [page (bd/pages bm)]
    (let [place (if (bd/daily-notes-page? page) "journals" "pages")]
      (md/write-page bm page (str directory place "/" (md/md-file-name (:content page)))))))

(defn -main
  [edn-file output-dir & args]
  (reset-directory output-dir)
  (let [bm (roam/roam-db-edn edn-file)
        images (roam-images/download-images bm output-dir)
        xbm (roam-images/subst-images bm images)
        ]
    (reset! last-bm xbm)
    (write-pages xbm output-dir)))

#_
(-main "~/Downloads/Sean_Stewart.edn" "/opt/mt/working/stewart/")

;;; TODO link in
#_
(defn convert-twitter-links
  [l]
  (let [link (re-find #"http.*twitter.com/\S*" l)]
    (when (and link
               (not (re-find #"\{\{tweet" l)))
      (str/replace l  #"http.*twitter.com/\S*" (format "{{tweet %s}}" link)))))
