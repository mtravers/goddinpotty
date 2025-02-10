(ns goddinpotty.import.logseq-test
  (:require [clojure.test :refer :all]
            [goddinpotty.import.logseq :as sut]
            [goddinpotty.utils :as utils]
            [goddinpotty.config :as config]
            [org.candelbio.multitool.core :as u]
            ))

;;; These are sort of high-level integration tests, to prove that the basic machinery is working.

;;; TODO requires test graph at test/resources/logseq-test be registered with Logseq

(deftest nbb-extract-test
  (let [extract (sut/nbb-extract "logseq-test")]
    (is (seq? extract))
    (is (> (count extract) 7))          ;this is going to grow over time
    (let [block (u/some-thing #(= "A [[page]] link"
                                  (:block/content %))
                              extract)]
      (= 1 (count (:block/refs block))))))

(deftest produce-bm-test
  (let [config (config/read-config "resources/test/logseq-test-config.edn")
        bm (sut/produce-bm config)]
    (is (map? bm))
    (let [{:keys [parsed refs] :as block}
          (u/some-thing #(= "A page link to [[some actual content]]"
                                  (:content %))
                              (vals bm))]

      (is (= [:block "A page link to " [:page-link "[[some actual content]]"]] parsed))
      (is (set? refs))
      (is (= 1 (count refs)))
      (let [ref-block (get bm (first refs))]
        (is (:page? ref-block))
        (is (= "some actual content" (:title ref-block)))))))


  
