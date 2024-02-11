(ns goddinpotty.context
  )

;;; A thin layer on top of existing dynamic binding functionality, making
;;; it a bit more usable. Mostly for ease in debugging.

;;; TODO integrate with error handling? Don't I have some macrology for this somewhere?
;;; yes this is kind of useless since it doesn't put anything on the stack

;;; → multitool, esp since it has no dependencies
;;; multitool.dev has debuggable, which is sort of same idea


(def ^:dynamic *context* {})

(def original-ex-info ex-info)

(defmacro with-context
  [[tag value] & body]
  `(binding [*context* (assoc *context* ~tag ~value)]
     (with-redefs [ex-info ~(fn [& args]           ;Make the context available for all clj exceptions (unfortunately won't do anything about normal java errors)
                              (apply original-ex-info (assoc-in (vec args) [1 :context] *context*)))]
       ~@body)))

(defn get-context
  ([tag]
   (get *context* tag))
  ([]
   *context*))

;;; Foo, errors have to check this explicitly, that sucks.




       
