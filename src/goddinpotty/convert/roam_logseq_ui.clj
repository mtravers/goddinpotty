(ns goddinpotty.convert.roam-logseq-ui
  (:require [seesaw.core :as ss]
            [seesaw.mig :as sm]
            [goddinpotty.convert.roam-logseq :as rl])
  (:gen-class))

;;; TODO option for not downloading files
;;; TODO Disable convert until ready
;;; TODO (?) actual incremental log stream.
;;; TODO Error handling
;;; TODO credits, link etc

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
    (.showSaveDialog fc frame)
    (.getSelectedFile fc)))

(defn convert
  [log-widget]
  ;; TODO redirect output somehow
  (prn :parameters @parameters)
  (let [log (with-out-str
              (rl/do-it (:input-file @parameters) (:output-dir @parameters) ))]
    (ss/text! log-widget log)
    ))

(defn change-label
  [event label]
  (let [button (.getSource event) ]
    (.setLabel button label)))

(def welcome
  "Welcome to RoamAway.

This tool will convert a Roam EDN export file into a Markdown repository suitable for Logseq.

To use, select the input and outputs above, and hit the Convert button.

RoamAway is Spiteware and free to use, tips gratefully acccepted.")

(defn make-ss-frame
  []
  (let [log-widget (ss/text :multi-line? true
                            :editable? false
                            :rows 30
                            :text welcome)
        the-frame (atom nil)]
    (reset!
     the-frame     
     (ss/frame :title "RoamAway"
               :content (sm/mig-panel
                         :items [
                                 ["Welcome to RoamAway" "wrap"]
                                 [(ss/button :text "Set input"
                                            :listen [:action (fn [e]
                                                               (let [f (select-input-file @the-frame)]
                                                                 (swap! parameters assoc :input-file f)
                                                                 (change-label e (str f))))])
                                  "wrap"]
                                 [(ss/button :text "Set output"
                                            :listen [:action (fn [e]
                                                               (let [f (select-output-directory @the-frame)]
                                                                 (swap! parameters assoc :output-dir f)
                                                                 (change-label e (str f))))])
                                  "wrap"]
                                 ;; Display file names
                                 [(ss/button :text "Convert"
                                             :listen [:action (fn [_] (convert log-widget))])
                                  "wrap"]
                                 [log-widget]
                                 ])
               ;; off for dev
               #_ :on-close #_ :exit
               ))
    (ss/pack! @the-frame)
    (ss/show! @the-frame)
    ))
