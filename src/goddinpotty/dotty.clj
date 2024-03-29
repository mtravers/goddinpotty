(ns goddinpotty.dotty
  (:require [goddinpotty.batadase :as bd]
            [clojure.string :as s]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            ))

;;; Status: not used and probably doesn't work

(defn- sh-errchecked
  [& args]
  (let [res (apply shell/sh args)]
    (when-not (= (:exit res) 0)
      (throw (Exception. (str "Bad result from shell" res))))
    res))


;;; To use this, do 'brew install graphviz' first (on Mac)
(def dot-command "/usr/local/bin/dot")

(defn- write-graphviz
  [block-map dot-file]
  (let [clean (fn [x] (str \" x \"))    ;TODO need to quote quotes
        attributes (fn [m & [sep]]
                     ;; For some reason graph attributes need a different separator
                     (s/join (or sep ",") (map (fn [[k v]] (format "%s=%s" (name k) (pr-str v))) m)))
        pages (bd/displayed-pages block-map)
        ]
    (println "Writing " dot-file)
    (with-open [wrtr (io/writer dot-file)]
      (binding [*out* wrtr]
        (println "digraph schema {")
        (println (attributes
                  { ; :rankdir "LR"
                    ; :size "14,20"
                   }
                  ";"))
        (doseq [page pages]
          (println (format "%s [%s];"
                           (clean (:content page))
                           (attributes {; :URL (kind-url kind)
                                        :label (:content page)
                                        ; :style "filled"
                                        ; :fillcolor (if (get-in kinds [kind :reference?]) reference-color nonreference-color)
                                        ; :fontname graph-font
                                        })))
          (doseq [ref (bd/page-refs page)]
            (println (format "%s -> %s [%s];"
                             (clean (:content page))
                             (clean ref)
                             (attributes {})))))
        (println "}"))
      (println "Generating .png")
      (sh-errchecked
       dot-command
       dot-file
       "-Tpng"
       "-O"
       "-Tcmapx"
       "-O"
       )
      )))
