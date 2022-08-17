(defproject goddinpotty "2.0.1"
  :description "A static-site generator for Roam Research"
  :url "https://github.com/mtravers/goddinpotty"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [me.raynes/fs "1.4.6"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/data.xml "0.2.0-alpha6"] ;just for blogger
;                 [org.clojars.simon_brooke/html-to-md "0.3.0"] 
;; Not suitable, it only renders html
;;                 [markdown-clj "1.10.7"]
;; Used by logseq-from-md, not there yet
;;                 [org.commonmark/commonmark "0.18.0"]
                 [html-to-md/html-to-md "0.3.0"]
                 [org.parkerici/multitool "0.0.22"]
                 [com.taoensso/truss "1.6.0"]
                 [alandipert/enduro "1.2.0"] ;persistence for expensive calculations
                 [hiccup "1.0.5"]
                 [instaparse "1.4.12"]
                 [aero "1.1.6"]
                 [metasoarous/oz "1.6.0-alpha34"] 
                 [mock-clj "0.2.1"]     ;TODO should be under :test profile
                 [seesaw "1.5.0"]
                 ]
  :resource-paths ["resources"]
  :main goddinpotty.core
  :aliases {"roamaway" ["run" "-m" "goddinpotty.convert.roam-logseq"]}
  :profiles
  {:uberjar
   {:aot :all
    :omit-source true
    }
   }
  )
