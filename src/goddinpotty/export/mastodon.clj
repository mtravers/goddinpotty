(ns goddinpotty.export.mastodon
  (:require [goddinpotty.batadase :as bd]
            [goddinpotty.rendering :as r]
            [goddinpotty.utils :as utils]
            [goddinpotty.config :as config]
            [clj-http.client :as client]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [org.candelbio.multitool.core :as u]
  ))

;;; Note: requires parsing whole graph, see database/parse
;;; But that doesn't work, refs still are limited. Argh.
(defn blog-blocks
  [bm]
  (bd/with-tag bm "blog"))  

(defn with-tag-complete
  [bm tag]
  (let [sharped (str "#" tag)]
    (filter (fn [block]
              (u/walk-find #(= % [:hashtag sharped])
                           (:parsed block)))
            (vals bm))))

(defn toot-blocks
  [bm]
  (with-tag-complete bm "toot"))

*(defmulti elt-toot-text first)

(defmethod elt-toot-text :default [elt]
  (str/join " " (map #(if (string? %) % (elt-toot-text %)) (rest elt))))

(defmethod elt-toot-text :alias [elt]
  (let [[_ alias-text alias-dest] (r/parse-alias (second elt))]
    (format "[%s] %s" alias-text alias-dest)))

(defmethod elt-toot-text :hashtag [elt]
  "")                                   ;want to eliminate blog, but probably others (or turn into links maybe)
  

;;; Well this is not optimal, but no way to specify link text
;;; TODO check if page is actually public, warn user if not
(defmethod elt-toot-text :page-link [elt]
  (let [title (utils/remove-double-delimiters (second elt))
        url (str (config/config :real-base-url) (utils/clean-page-title title))]
    url))

(defn block-toot-text-1
  [block-map block]
  #_ (prn :btt-1 block (elt-toot-text (:parsed block)))
  (elt-toot-text (:parsed block)))

(defn block-toot-text
  [block-map block]
    (str/join " " (cons (block-toot-text-1 block-map block)
                        (map #(block-toot-text block-map (get block-map %))
                             (:children block))))
  )


(defn get-auth-code
  []
  )

;;; From browser
(def auth-code "WutfYNiHxA_0NGRugrPTINvgtkPZbC0Sm6QHBRtUcnM")


(defn get-access-token
  []
  ;; TODO extract access token
  (client/post "https://mastodon.social/oauth/token"
               {:query-params {:client_id "CVSY3Z4uDp-MyZohKk9zgJ1ZyD4eSg_RHHsyZTyQMMo"
                               :client_secret "32lgOi4mPyt3AQmwCPICBZnVcWBYbKFTo4k7ojZ8TUA"
                               :redirect_uri "urn:ietf:wg:oauth:2.0:oob"
	                       :grant_type "authorization_code"
	                       :code auth-code}
                }))

;;; Should do a length See
;;; checkt https://docs.joinmastodon.org/methods/statuses/#create
;;; TODO Error handling
;;; TODO maybe reduce double spaces
;;; TODO check for {{}} and other icky constructs?
(defn post
  [text]
  (let [resp
        (client/post "https://mastodon.social/api/v1/statuses"
               {:query-params {:status text}
                :headers {:Authorization (str "Bearer " (config/config :mastodon-token))}})]
    (json/read-str (:body resp) :key-fn keyword )))
               
