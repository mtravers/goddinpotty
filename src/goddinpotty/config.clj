(ns goddinpotty.config
  (:require [aero.core :as aero]
            [clojure.string :as s]
            [clojure.pprint :as pprint]
            ))

;;; For config specifics, see resources/default-config.edn

(defmethod aero/reader 'split
  [_ _ [s]]
  (and s (s/split s #",")))

(def the-config (atom {}))

(defn set-config-map!
  [m]
  (reset! the-config m)
  (pprint/pprint @the-config)
  m)

(defn read-config
  [path]
  (aero/read-config path))

(defn set-config-path!
  [path]
  (set-config-map! (read-config path)))

;;; TODO should take paths
(defn config
  [& atts]
  (if atts
    (get-in @the-config atts)
    @the-config))



