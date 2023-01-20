(ns goddinpotty.import.edit-times
  (:require [org.parkerici.multitool.core :as u]
            [clojure.java.shell :as sh]
            [me.raynes.fs :as fs]
            [goddinpotty.config :as config]
            [goddinpotty.endure :as e]
            [goddinpotty.utils :as utils]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            )
  )

;;; Was in logseq, not needed any more and its kind of its own thing.
;;; Some of this would be good for multitool?

;;; Determined empirically. This still fails on a few files
(defn clean-page-name
  [n]
  (-> n
      (str/replace #"/" ".")
      ;;
      #_ (str/replace #"/" "-")            ;sometimes this becomes a . Argh
      (str/replace #"[:\?\"]" "_")  ; \(\) but these seem just as often passed through...argh
      ))

;;; Alternative to this shell nonsense https://www.eclipse.org/jgit/
(def git-date-formatter
  (java.text.SimpleDateFormat. "yyyy-MM-dd hh:mm:ss ZZZZZ"))

(defn parse-git-date
  [s]
  (.parse git-date-formatter s))

;;; TODO These are way too slow for practical use; need to cache the data across runs somehow, which is a pain.
;;; Hm, I want def-memoized but persistant...

;;; This saves a full 5 minutes in hyperphor build (as of 2/15/2022).
(e/defn-memoized git-first-mod-time
  [f]
  (-> (sh/sh "git" "log"
             "-M"                       ;follow renames
             "--reverse"
             "--date=iso"
             "--format=\"%ad\""
             "--follow"
             "--" f 
             :dir (get-in (config/config) [:source :repo]))
      :out
      (str/split #"\n")                 ;Note: sh/sh can't do pipes else this would be head -l
      first
      (utils/strip-chars #{\" \newline})
      parse-git-date
      )
  )

;;; Unlike git-first-mod-time, the value of this will change over time.
;;; So this uses the file write time as a second key for the persistence lookup
;;; So changes should be detected.
;;; This will accumulate old cruft in the persistence store, but it can
;;; be deleted at will, so...
;;; TODO smarter store where it only keeps the last value of certain keys
(e/defn-memoized git-last-mod-time-1
  [f time]
  (-> (sh/sh "git" "log" "-1"
             "--grep=Logseq"            ;kludge, exclude some manual bookkeeping commits LOGSEQ 
             "--date=iso"
             "--format=\"%ad\""
             "--" f
             :dir (get-in (config/config) [:source :repo]))
      :out
      (utils/strip-chars #{\" \newline})
      ((u/saferly parse-git-date))))

(defn git-last-mod-time
  [f]
  (git-last-mod-time-1 f (fs/mod-time f)))

(defn safe-times
  [f]
  (u/ignore-report
   [(git-first-mod-time f)
    (git-last-mod-time f)]
   ))

(defn page-date-range
  [{:keys [file title id] :as page}]
  (if (fs/exists? file)                ;TODO this is not adequate check due to retarded case folding
    (safe-times file)
    (log/warn "File not found" file "for" title))) ;return nil

(defn page-edit-time
  [page]
  (second (page-date-range page)))


