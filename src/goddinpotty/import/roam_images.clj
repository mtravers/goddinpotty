(ns goddinpotty.import.roam-images
  (:require [org.parkerici.multitool.core :as u]
            [org.parkerici.multitool.cljcore :as ju]
            [me.raynes.fs :as fs]
            [goddinpotty.batadase :as bd]
            [goddinpotty.rendering :as render]
            ))

(defn page-images
  [bm]
  (u/collecting
   (fn [collect]
     (u/map-values
      (fn [block]
        (when-let [match (and (string? (:content block))
                              (re-matches #"^!\[\]\((.*)\)" (:content block)))]
          (collect [(:id block) (:id (bd/block-page bm block)) (second match)])))
      bm))))

;;;; Um maybe not
#_
(defn image-copy-script
  [bm dir]
  (doseq [[id page url] (page-images bm)]
    (let [file (format "%s/%s-%s.png" dir page id)]
      (println (format "curl \"%s\" > \"%s\"" url file)))))

(defn roam-image?
  "Returns the extension if this is in fact a roam image, nil otherwise"
  [url]
  (second (re-matches #"https\:\/\/firebasestorage\.googleapis\.com/.*\.(\w+)\?.*" url)))

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
  [bm directory]
  (doseq [image-block (filter #(= :image (first (second (:parsed %)))) (vals bm))]
    (let [[image-source alt-text] (render/parse-image-block image-block)]
      ;; See rendering/format-image
      (when-let [ext (roam-image? image-source)]
        ;; TODO has failure modes if page name contains / ! and maybe other chars. 
        (let [local-filename (str directory (:title (bd/block-page bm image-block)) "-" (:id image-block) "." ext)]
          (prn :download local-filename image-source)
          (local-file image-source local-filename))))))
