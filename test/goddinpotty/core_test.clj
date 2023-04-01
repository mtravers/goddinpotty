(ns goddinpotty.core-test
  (:require [clojure.test :refer :all]
            [goddinpotty.core :refer :all]
            [org.parkerici.multitool.core :as u]
            [me.raynes.fs :as fs]
            ))

;;; bin/serve.sh 3889 "target/logseq-test"

(defn file-contains?
  [f str]
  (re-find (u/re-pattern-literal str) (slurp f)))


;;; Test build from Logseq
(deftest generate-from-logseq
  (fs/delete-dir "target/logseq-test")
  (main "resources/test/logseq-test-config.edn")
  (is (fs/exists? "target/logseq-test/Superman"))
  (testing "link from front page to clark kent/superman is right"
    (is (fs/exists? "target/logseq-test/Front-Page"))
    (is (file-contains? "target/logseq-test/Front-Page" "<a href=\"Superman\">Clark Kent</a>")))

  ;; MORE!
  )
