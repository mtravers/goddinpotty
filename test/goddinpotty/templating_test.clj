(ns goddinpotty.templating-test
  (:require [goddinpotty.templating :refer :all]
            [goddinpotty.database :as db]
            [org.candelbio.multitool.core :as u]
            [clojure.test :refer :all]))

;;; → utils
(defn structure-contains?
  [elt struct]
  (u/walk-find #(= % elt) struct))

(deftest formatted-page-title-test
  (let [id 23
        page 
        '{:include? true,
          :title "__On Purpose__",
          :refs #{},
          :edit-time #inst "2021-05-31T03:47:57.271-00:00"
          :page? true,
          :id id ,
          :depth 4,
          :heading -1}
        hiccup (block-page-hiccup id {id page} "output") ]
    (is (structure-contains? [:h1 [:i "On Purpose"]] hiccup))))
