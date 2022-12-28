(ns goddinpotty.context
  )

;;; A thin layer on top of existing dynamic binding functionality, making
;;; it a bit more usable. Mostly for ease in debugging.

;;; TODO integrate with error handling? Don't I have some macrology for this somewhere?
;;; yes this is kind of useless since it doesn't put anything on the stack

;;; → multitool, esp since it has no dependencies
;;; multitool.dev has debuggable, which is sort of same idea


(def ^:dynamic *context* {})

(defmacro with-context
  [[tag value] & body]
  `(binding [*context* (assoc *context* ~tag ~value)]
     ~@body))

(defn get-context
  ([tag]
   (get *context* tag))
  ([]
   *context*))
