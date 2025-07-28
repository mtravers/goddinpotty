(ns goddinpotty.import.roam-logseq-test
  (:require [goddinpotty.import.roam-logseq :refer :all]
            [clojure.test :refer :all]))

(deftest integration-test
  (-main "test/resources/roamaway.edn"))
