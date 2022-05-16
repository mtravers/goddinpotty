(ns goddinpotty.core-test
  (:require [clojure.test :refer :all]
            [goddinpotty.core :refer :all]
            [goddinpotty.database :as database]
            [goddinpotty.utils :as utils]
            [org.parkerici.multitool.core :as u]
            [me.raynes.fs :as fs]
            ))

;;; Test build from Logseq
(deftest generate-from-logseq
  (fs/delete-dir "taget/logseq-test")
  (main "test/logseq-test-config.edn")
  (is (fs/exists? "target/logseq-test/Superman"))
  ;; MORE!
  )
