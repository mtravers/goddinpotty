(ns goddinpotty.import.roam-images
  (:require [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [me.raynes.fs :as fs]
            [goddinpotty.batadase :as bd]
            [goddinpotty.rendering :as render]
            [goddinpotty.utils :as utils]
            [clojure.string :as s]
            ))

(defn- roam-image-url?
  "Returns the extension if this is in fact a roam image, nil otherwise"
  [url]
  (second (re-matches #"https\:\/\/firebasestorage\.googleapis\.com/.*\.(\w+)\?.*" url)))

(defn- image-block?
  [b]
  (= :image (first (second (:parsed b)))))

(defn- roam-image-block?
  [b]
  (and (image-block? b)
       (let [[image-source alt-text] (render/parse-image-block b)]
         (roam-image-url? image-source))))

;;; Now in ju/
(defn local-file
  ([url]
   (local-file url (ju/temp-file)))
  ([url local-file]
   (let [url (java.net.URL. url)
         local-file (if (instance? java.io.File local-file)
                      local-file
                      (java.io.File. local-file)
                      )]
     (clojure.java.io/copy (.openStream url) local-file)
     (str local-file))))

(defn download-images
  "Returns a map of original URLs to local files (relative path)"
  [bm directory]
  (u/collecting-merge
   (fn [collect]
     (doseq [image-block (filter image-block? (vals bm))]
       (identity #_ u/ignore-report
        (let [[image-source alt-text] (render/parse-image-block image-block)]
          ;; See rendering/format-image
          (when-let [ext (roam-image-url? image-source)]
            (let [base-filename (str (utils/clean-page-title (:content (bd/block-page bm image-block))) "-" (:id image-block) "." ext)
                  local-relative (str "assets/" base-filename )
                  local-full (str directory "/" local-relative)
                  url (str "../assets/" base-filename)
                  ]
              (prn :download base-filename image-source)
              (local-file image-source local-full)
              (collect {image-source url})))))))))

(defn- subst-image-source
  [str substs]
  (let [[image-source alt-text] (render/parse-image-string str)
        replacement (get substs image-source)]
    (if replacement
      (s/replace str image-source replacement)
      (do (prn "replacement not found for " str)
          str))))

(def c (atom nil))

;;; This totally doesn't work unless its done before :dchildren hack
(defn subst-images
  "Toplevel call"
  [bm substs]
  (reset! c [bm substs])
  (u/map-values
   (fn [b]
     (if (roam-image-block? b)
       ;; Note: there are two representations in a block :content and :parsed, but for now tweaking :content will suffice
       (assoc b
              :content
              (subst-image-source (:content b) substs))
       (dissoc b :dchildren)))          ;avoid confusion
   bm))

