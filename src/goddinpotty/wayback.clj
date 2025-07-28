(ns goddinpotty.wayback
  (:require [clj-http.client :as client]
            [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.cljcore :as ju]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as sh]
            [goddinpotty.endure :as e]
            [me.raynes.fs :as fs]
            ))

;;; Curation tool, refresh dead links by changing them to point to archive.org


;;; TODO spin this out into its own thing, maybe a CLI tool that can process a file or dir of markdown
;;; See also https://github.com/mtravers/waybacker
;;; and/or put under goddinpotty.curate.wayback maybe

;;; Returns a JSON struct like
;; {"url" "http://alumni.media.mit.edu/~mt/diss/index.html",
;;  "archived_snapshots"
;;  {"closest"
;;   {"status" "200",
;;    "available" true,
;;    "url"
;;    "http://web.archive.org/web/20230625052429/https://alumni.media.mit.edu/~mt/diss/index.html",
;;    "timestamp" "20230625052429"}}}
;;; TODO is this newest? There are args for specifying
;;; TODO can get rate limited, gets 429 status in that case. Need backoff or something. Sigh.
(u/defn-memoized wayback
  "Look up a URL at archive.org"
  [url]
  (let [resp (client/get "http://archive.org/wayback/available"
                         {:query-params {:url url}
                          :as :json})]
    (:body resp)))

;;; Add a flag to wayback url
;;; See https://en.wikipedia.org/wiki/Help:Using_the_Wayback_Machine#Specific_archive_copy
;;; See https://webapps.stackexchange.com/questions/40911/web-archive-links-without-header
(defn wayback-url-flagged
  [url flag]
  (let [[[s e]] (u/re-seq-positions #"/\d+/" url)]
    (if e
      (str (subs url 0 (- e 1))
           flag
           (subs url (- e 1)))
      url)))

(defn replacement-url
  "Generate a replacement, or return the original if that won't work"
  [url]
  (let [resp (wayback url)
        new-url (get-in resp [:archived_snapshots :closest :url])]
    ;; TODO wanted logging here but it gets into the file! Argh
    (if new-url
      (wayback-url-flagged new-url "if_") ; add flag that hides wayback header
      url)))

;;; ???
#_
(defn url-pattern
  [string]
  (re-pattern (str "http\\S*" (u/re-quote string) "[^\\s\\)\\]]*")))

;;; No parens
(def url-pattern #"https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")

;;; TODO should be smart and detect of url is already waybacked
(defn process-string
  [s]
  (s/replace s url-pattern replacement-url))

#_
(defn process-file
  [pattern f]
  ;; TODO NO!!!!! This fucks up line endings. Do the whole file as single string
  #_ (ju/process-file-lines (partial process-string pattern) f)
  (->> f 
       slurp
       (process-string pattern)
       (spit f)))

#_
(defn process-files
  [pattern d]
  (doseq [f (ju/list-dir-recursive d)]
    (process-file pattern f)))
      
;;; Why am I doing patterns? Check if URL exists or not?




;;; exceptions:
;;; - twitter tweets get errors even when they exist.
(def exceptions
  #{"twitter.com"                       ;403
    "web.archive.org"                   ;already archived
    })

;;; TODO handle 301s

;;; TODO too many false negatives, just call out to curl
;;; True if good, exception of bad
(u/defn-memoized url-good-clj?
  [url get?]
  (prn :testing url)
  (if (some #(s/includes? url %) exceptions)
    true                                ;TODO ought to return something meaningful
    (try
      ((if get? client/get client/head) url)
      true
      (catch Exception e
        (if (and (not get?) (= 405 (:status (ex-data e)))) 
          (url-good-clj? url true)
          e)))))

;;; -L means bad redirects generate errors
(u/defn-memoized url-good-sh?
  [url]
  (or (= 0 (:exit (sh/sh "curl" "--head" "-f" "-L" url)))
      (= 0 (:exit (sh/sh "curl" "-f" "-L" url)))))

(defn url-good?
  [url]
  (or (= true (url-good-clj? url false))
      (url-good-sh? url)))

;;; Return list of bad urls
(e/defn-memoized analyze-file
  [f]
  (->> f
       slurp
       (re-seq url-pattern)
       (remove url-good?)))

(defn analyze-files
  [d]
  (into {}
        (for [f (ju/list-dir-recursive d)]
          [(fs/base-name f) (analyze-file (str f))])))


         
;;; Runner

(defn fix-file
  [f]
  (let [orig (slurp f)
        urls (analyze-file f)
        bad-urls (map first (u/dissoc-if (fn [[k v]] (= v true)) urls))]
    (prn :fix f bad-urls)
    (spit f (reduce (fn [s url]
                      (s/replace s url (replacement-url url)))
                    orig
                    bad-urls))))


(defn runner
  [dir]
  (let [file-urls (analyze-files dir)]
    (ju/schppit file-urls "file-urls.edn")
    (doseq [[f urls] file-urls]
      (let [orig (slurp f)]
        (spit f (reduce (fn [s [url good]]
                          (if good
                            s
                            (s/replace s url (replacement-url url))))
                        orig
                        urls))))))


;;; Entry points - expose these as cli commands somehow TODO
;; - check url
;; - replace url
;; - check and replace url
;; - proceess file
;; - process dir
