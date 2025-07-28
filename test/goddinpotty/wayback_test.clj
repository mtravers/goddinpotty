(ns goddinpotty.wayback-test
  (:require [goddinpotty.wayback :refer :all]
            [clojure.test :refer :all]))

(def s "- [Programming with Agents](https://alumni.media.mit.edu/~mt/diss/index.html)")

(deftest basic-wayback
  (let [res (process-string s)]
    (is (re-matches #"- \[Programming with Agents\]\(http://web.archive.org/web/.*\)" res))))

;;; TODO test org-mode and other syntaxes

