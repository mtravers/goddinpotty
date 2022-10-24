(ns goddinpotty.batadase-test
  (:require [goddinpotty.batadase :as sut]
            [goddinpotty.utils :as utils]
            [goddinpotty.config :as config]
            [goddinpotty.core :as core]
            [mock-clj.core :as mc]
            [clojure.test :refer :all]))

(defn test-bm
  []
  (->  "resources/test/logseq-test-config.edn"
       config/set-config-path!
       core/produce-bm))


(defn find-block
  [bm content]
  (let [blocks (filter #(= (:content %) content) (vals bm))]
    (when-not (= 1 (count blocks))
      (throw (ex-info "Failed to find unique block" {:content content :blocks blocks})))
    (first blocks)))

(defn displayed?
  [block]
  (:include? block))


(deftest tagging-test 
  (let [bm (test-bm)]
    (is (displayed? (find-block bm "This content should be visible")))
    (is (not (displayed? (find-block bm "This content should be private"))))
    (is (displayed? (find-block bm "This should be visible")))
    (is (not (displayed? (find-block bm "This should also be private"))))))
    
