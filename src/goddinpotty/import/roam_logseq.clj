(ns goddinpotty.export.roam-logseq
  (:require [goddinpotty.export.markdown :as md]
            [goddinpotty.import.edn :as roam]
            ))

#_
(utils/unzip-roam "/opt/mt/working/roam-takeout/mt-pici/Roam-Export-1636387068791.zip")

;;; TODO painfully slow for some reason, try to speedup if releasing as a standalone tool
;;; TODO generating a shit-ton of zero-length files, should omit 
;;; TODO image asseets, that was the whole freaking point.

(def last-bm (atom {}))

(defn -main
  [edn-file output-dir & args]
  (let [bm (roam/roam-db-edn edn-file)]
    (reset! last-bm bm)
    (md/write-pages bm output-dir)))

#_
(-main "~/Downloads/Sean_Stewart.edn" "/tmp/stewart/")


;;; TODO link in
#_
(defn convert-twitter-links
  [l]
  (let [link (re-find #"http.*twitter.com/\S*" l)]
    (when (and link
               (not (re-find #"\{\{tweet" l)))
      (str/replace l  #"http.*twitter.com/\S*" (format "{{tweet %s}}" link)))))
