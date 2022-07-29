(ns goddinpotty.convert.roam-logseq-ui
  (:require [seesaw.core :as ss]
            [seesaw.mig :as sm]
            [org.parkerici.multitool.cljcore :as ju]
            [goddinpotty.convert.roam-logseq :as rl])
  (:gen-class))

;;; Top-level for package-app version of RoamAway

;;; TODO option for not downloading files
;;; TODO Make sure can copy from output.
;;; TODO Windows release process

(def root (atom nil))                   ;holds the java frame object
(def parameters (atom {}))              ;map of parameter values

(def welcome
  "Welcome to RoamAway.

This tool will convert a Roam EDN export into a Markdown repository suitable for Logseq.

To use, select the input and outputs above, and hit the Convert button.

RoamAway is open source and free to use, tips gratefully acccepted.")

(defn set-parameter
  [param value]
  (swap! parameters assoc param value)
  (when (and (:input-file @parameters) (:output-dir @parameters))
    (.setEnabled (ss/select @root [:#convert]) true)))

(defn select-input-file
  [frame]
  (let [fc (javax.swing.JFileChooser.)]
    (.setFileFilter fc (javax.swing.filechooser.FileNameExtensionFilter.
                        "Roam export (.edn or .zip)"
                        (into-array ["edn" "zip"])))
    (.showOpenDialog fc frame)
    (.getSelectedFile fc)))

(defn select-output-directory
  [frame]
  (let [fc (javax.swing.JFileChooser.)]
    (.showSaveDialog fc frame)
    (.getSelectedFile fc)))

(defn change-label
  [event label]
  (let [button (.getSource event) ]
    (.setLabel button label)))

(defn about
  []
  (ju/open-url "http://hyperphor.com/ammdi/RoamAway"))

(defn widget-output-writer
  [widget]
  (proxy [java.io.Writer] []
    (write [x]
      (let [xx (str (if (int? x) (char x) x))]
        (.append widget xx)))
    (flush [])))

(defn convert
  [log-widget]
  (ss/text! log-widget "Converting!\n")
  (prn :parameters @parameters)
  (binding [*out* (widget-output-writer log-widget)]
    (try
      (rl/do-it (:input-file @parameters) (:output-dir @parameters) )
      (catch Throwable e
        (println (str "ERROR: " (print-str e)))))
    (println "Done!")))

(defn make-ss-frame
  []
  (let [log-widget (ss/text :multi-line? true
                            :editable? false
                            :rows 40
                            :columns 80
                            :text welcome)
        frame
     (ss/frame :title "RoamAway"
               :content (sm/mig-panel
                         :items [[(sm/mig-panel
                                  :items [["Welcome to RoamAway"]
                                          ;; TODO should look like a hyperlink
                                          [(ss/button :text "About"
                                                      :listen [:action (fn [_] (about))])]
                                          [(ss/button :id :convert
                                                      :text "Convert"
                                                      :enabled? false
                                                      :listen [:action (fn [_] (convert log-widget))])
                                           "push, align right"]])
                                  "growx, wrap"]
                                 [(ss/button :text "Set input"
                                            :listen [:action (fn [e]
                                                               (let [f (select-input-file @root)]
                                                                 (set-parameter :input-file f)
                                                                 (change-label e (str f))))])
                                  "wrap"]
                                 [(ss/button :text "Set output"
                                             :listen [:action (fn [e]
                                                               (let [f (select-output-directory @root)]
                                                                 (set-parameter :output-dir f)
                                                                 (change-label e (str f))))])
                                  "wrap"]
                                 ;; Display file names


                                 [(ss/scrollable log-widget) "push, growx, growy, align right bottom"]]

                                 )
               :on-close :exit
               )
        ]
    (reset! root frame)
    (ss/pack! frame)
    (ss/show! frame)
    ))

(defn -main
  []
  (make-ss-frame))
