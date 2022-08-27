(ns goddinpotty.import.roam-images
  (:require [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [me.raynes.fs :as fs]
            [goddinpotty.batadase :as bd]
            [goddinpotty.rendering :as render]
            [goddinpotty.utils :as utils]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            ))

;;; Was just images, now will do arbitrary assets like pdfs regardless of where they appear in
;;; markdown.

;;; Regex that matches Roam assets, returns filetype as second elt of match
(def roam-asset-regex #"https\:\/\/firebasestorage\.googleapis\.com/.*\.(\w+)\?.*")

(defn- roam-asset-url?
  "Returns the extension if this is in fact a roam asset, nil otherwise"
  [url]
  (second (re-matches roam-asset-regex url)))

(defn- image-block?
  [b]
  (= :image (first (second (:parsed b)))))

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

(defn block-links
  [block]
  (u/walk-collect #(and (string? %)
                        (s/starts-with? % "http")
                        %)
                  (:parsed block)))

(defn- maybe-download-url
  [bm directory block url collect]
  (when-let [ext (roam-asset-url? url)]
    (let [base-filename (str (utils/clean-page-title (:content (bd/block-page bm block))) "-" (:id block) "." ext)
          local-relative (str "assets/" base-filename )
          local-full (str directory "/" local-relative)
          new-url (str "../assets/" base-filename)
          ]
      (if (fs/exists? local-full)
        (log/info :already-downloaded base-filename url)
        (do
          (log/info :download base-filename url)
          (local-file url local-full)))
      (collect {url new-url}))))

(defn download-images
  "Returns a map of original URLs to local files (relative path)"
  [bm directory]
  (u/collecting-merge
   (fn [collect]
     (doseq [block (vals bm)]
       (when (image-block? block)
         (let [[image-source _] (render/parse-image-block block)]
           (maybe-download-url bm directory block image-source collect)))
       (doseq [link (block-links block)] ;TODO if this was block-links-unparsed we could skip parse entirely
         (maybe-download-url bm directory block link collect))))))

(defn- subst-image-source
  [str substs]
  (let [[image-source alt-text] (render/parse-image-string str)
        replacement (get substs image-source)]
    (if replacement
      (s/replace str image-source replacement)
      str)))


;;; This totally doesn't work unless its done before :dchildren hack
(defn subst-images
  "Toplevel call"
  [bm substs]
  (reset! c [bm substs])
  (u/map-values
   (fn [b]
     ;; Now does every content. This might be slow as shit.
     (if true ; (roam-image-block? b)
       ;; Note: there are two representations in a block :content and :parsed, but for now tweaking :content will suffice
       (assoc b
              :content
              (subst-image-source (:content b) substs))
       (dissoc b :dchildren)))          ;avoid confusion
   bm))


;;; More general and stupider version

(defn block-links-unparsed
  [s]
  (map first (re-seq roam-asset-regex s)))

(defn subst-string
  [substs s]
  (reduce (fn [ss link]
            (if-let [replacement (get substs link)]
              (s/replace ss link replacement)
              (do (log/warn "No subst for" link s) ;shouldn't happen
                  ss)))
          s
          (block-links-unparsed s)))

(defn subst-images
  "Toplevel call"
  [bm substs]
  (u/map-values
   (fn [b]
     ;; Now does every content. This might be slow as shit.
     (update b
             :content
             (partial subst-string substs)))
   bm))
             
