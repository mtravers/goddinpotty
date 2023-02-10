(ns goddinpotty.endure
  (:require [alandipert.enduro :as e]
            [org.parkerici.multitool.core :as u]))

;;; TODO also in voracious, sync these up
;;; Generalized persistent memoizing based on Enduro https://github.com/alandipert/enduro

;;; This is a loose copy of stuff in multitool, modified to work with an enduro atom
;;; Enduro can support different backing stores, might want to support that.
;;; TODO would be better to have separate atom and file for each fn, if there are ever > 1. 
;;; → multitool except I don't want to bring along the dependencies (includes postgres)
;;; TODO should be a version of Enduro that backs onto gcs or other cloud service.

(def memoizers (e/file-atom {} ".enduro" :pending-dir "/tmp"))

;;; There are two different usages of "memoize" going on, which is confusing. This is a u/ memoizer
;;; that keeps track of the e/memoizers
(u/defn-memoized memoizer
  [name]
  (e/file-atom {}  (str ".enduro.d/" name) :pending-dir "/tmp"))

(defn memoize-named
  [name f]
  (fn [& args]
    (let [mem (memoizer name)]
      (if-let [e (find @mem args)]
        (val e)
        (let [ret (apply f args)]
          (e/swap! mem assoc args ret)
          ret)))))

(defmacro defn-memoized
  "Like `defn`, but produces a memoized and persisted function"
  [name args & body]
  ;; This crock is because you can't have multiple varadic arg signatures...sigh
  (if (string? args)
    `(def ~name ~args (memoize-named '~name (fn ~(first body) ~@(rest body))))
    `(def ~name (memoize-named '~name (fn ~args ~@body)))))

;;; Call with the quoted function name (eg (reset 'git-first-mod-time))
(defn reset!
  [& f]
  (if f
    (e/swap! memoizers update (first f) {})
    (e/reset! memoizers {})))

(defn reset-all!
  []
  (e/reset! memoizers {}))
