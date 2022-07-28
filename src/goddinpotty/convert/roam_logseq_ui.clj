(ns goddinpotty.convert.roam-logseq-ui
  (:require [seesaw.core :as ss]
            [goddinpotty.convert.roam-logseq :as rl])
  (:gen-class))

(def parameters (atom {}))

(defn select-input-file
  [frame]
  (let [fc (javax.swing.JFileChooser.)]
    (.setFileFilter fc (javax.swing.filechooser.FileNameExtensionFilter.
                        "Roam EDN Export"
                        (into-array ["edn"])))
    (.showOpenDialog fc frame)
    (.getSelectedFile fc)))

(defn select-output-directory
  [frame]
  (let [fc (javax.swing.JFileChooser.)]
    #_ (.setFileFilter (javax.swing.filechooser.FileNameExtensionFilter.
                     "Roam EDN Export"
                     (into-array ["edn"])))
    (.showSaveDialog fc frame)
    (.getSelectedFile fc)))

(defn convert
  [frame]
  ;; TODO redirect output somehow
  (prn :parameters @parameters)
  (rl/do-it (:input-file @parameters) (:output-dir @parameters) ))

(defn change-label
  [event label]
  (let [button (.getSource event) ]
    (.setLabel button label)))

(defn make-ss-frame
  []
  (let [the-frame (atom nil)
        f
        (ss/frame :title "RoamAway"
                  :content (ss/flow-panel
                            :items ["Hello"
                                    (ss/button :text "Set input"
                                               :listen [:action (fn [e]
                                                                  (let [f (select-input-file @the-frame)]
                                                                    (swap! parameters assoc :input-file f)
                                                                    (change-label e (str f))))])
                                    (ss/button :text "Set output"
                                               :listen [:action (fn [e]
                                                                  (let [f (select-output-directory @the-frame)]
                                                                    (swap! parameters assoc :output-dir f)
                                                                    (change-label e (str f))))])
                                    ;; Display file names
                                    (ss/button :text "Convert"
                                               :listen [:action (fn [_] (convert @the-frame))])
                                    (ss/text :multi-line? true
                                             :editable? false
                                             :rows 30)
                                    ])
                  ;; off for dev
                  #_ :on-close #_ :exit
                  )]
    (reset! the-frame f)
    (ss/pack! f)
    (ss/show! f)
    f))




