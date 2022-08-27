(ns goddinpotty.convert.roam-logseq-test
  (:require [goddinpotty.convert.roam-logseq :refer :all]
            [me.raynes.fs :as fs]
            [clojure.set :as set]
            [clojure.test :refer :all]))

(defn dir=
  [dir files]
  (= (set (map fs/base-name (fs/list-dir dir)))
     (set files)))

(defn dir-includes?
  [dir files]
  (set/subset? (set files)
               (set (map fs/base-name (fs/list-dir dir)))))

(deftest integration-test
  (let [dir (fs/temp-dir "roamaway")]
    (do-it "test/resources/roamaway.edn" dir)
    (is (dir= dir ["assets" "journals" "pages"]))
    (is (dir-includes? (str dir "/pages") ["Anonymous.md"
                                  "collisions.md"
                                  "Collisions+.md"
                                  "images.md"
                                  "tests.md"]))
    (is (dir= (str dir "/assets") ["images-rUaXDCFLG.png"]))
    ;; TODO journals, page content
    ))

    
