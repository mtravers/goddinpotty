(ns goddinpotty.utils
  (:require [me.raynes.fs :as fs]
            [taoensso.truss :as truss :refer (have?)]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [goddinpotty.config :as config]
            )
  (:import (java.util.zip ZipFile)))

(def last-import (atom nil))

(defn latest
  [dir pattern]
  (->> dir
       fs/expand-home
       fs/list-dir
       (filter (comp (partial re-find (re-pattern pattern)) str))
       (sort-by fs/mod-time)
       last
       (reset! last-import)
       str))

;;; TODO timezone correction
;;; Previously got from filename, but this is more general
(defn latest-export-time
  []
  (if @last-import
    (fs/mod-time @last-import)
    (ju/now)))

;;; read-edn see goddinpotty.import.edn

(defn read-json
  [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn write-json [f data]
  (fs/mkdirs (fs/parent f))             ;ensure directory exists
  (with-open [s (io/writer f)]
    (json/write data s)))

(defn remove-n-surrounding-delimiters
  "Removes n surrounding characters from both the beginning and end of a string"
  [n string]
  (subs string n (- (count string) n)))

(defn remove-double-delimiters
  "Removes 2 surrounding characters from both the beginning and end of a string"
  [string]
  (remove-n-surrounding-delimiters 2 string))

;;; Note: multitool has diff arg order for some reason
(defn strip-chars
  "Removes every character of a given set from a string"
  [s removed]
  (reduce str (remove #((set removed) %) s)))

(defn parse-hashtag
  [hashtag]
  (if (= \[ (second hashtag))
    (remove-double-delimiters (subs hashtag 1))
    (subs hashtag 1)))

(defn clean-page-title
  [string]
  (-> string
      (strip-chars #{\( \) \[ \] \? \! \. \@ \# \$ \% \^ \& \* \+ \= \; \: \" \' \\ \, \< \> \~ \` \{ \}}) ; experimentally removed  /
      (s/replace #"\s" "-")
      (s/replace #"\/" "∕")             ;that's "replace real slash with fake slash that won't make a subir"
      ))

(def date-formatter
  (java.text.SimpleDateFormat. "dd MMM yyyy hh:mm"))

;;; TODO fix time input
(defn coerce-time [x]
  (cond (inst? x) x
        (int? x) (java.util.Date. x)
        (string? x) (java.util.Date. x)
        :else x))

(defn render-time
  [time]
  (and time (.format date-formatter (coerce-time time))))         ;crude for now

(defn css-style
  [smap]
  (s/join " " (map (fn [[prop val]] (format "%s: %s;" (name prop) val)) smap)))
  

