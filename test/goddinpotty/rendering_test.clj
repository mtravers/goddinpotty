(ns goddinpotty.rendering-test
  (:require [goddinpotty.rendering :refer :all]
            [goddinpotty.parser :as parser]
            [goddinpotty.utils :as utils]
            [mock-clj.core :as mc]
            [clojure.test :refer :all]))

(defn with-config
  [file f]
  (goddinpotty.config/set-config-path! file)
  (f))

(defn with-rendering-config
  [f]
  (with-config "test/resources/rendering-test-config.edn" f))

(use-fixtures :once with-rendering-config)

;;; TODO add some aliases
(def fake-block-map
  {1 {:id 1 :page? true :include? true :display? true :content "foo" :title "foo"}
   2 {:id 2 :page? true :include? true :display? true :content "short" :title "bar"}
   3 {:id 3 :page? true :include? true :display? true :content (str (range 1000)) :title "baz"}})

(def block-id (atom 4))

(defn prep-block
  [b]
  (-> b
      (assoc :parsed (parser/parse-to-ast (:content b)))
      (assoc :display? true)
      (assoc :id (or (:id b) (swap! block-id inc)))))

(defn fake-block
  [content]
  (prep-block {:content content}))

(defn fake-block-map+
  [block]
  (assoc fake-block-map (:id block) block))

(deftest alias-html-test
  (is (= [:span "what " [:a.external {:href "fuck"} "the"] " is this"]
         (block-content->hiccup "what [the](fuck) is this")))
  (is (= [:span "what " [:a.external {:href "fuck"} "the fucking"] " is this"]
         (block-content->hiccup "what [the fucking](fuck) is this")))

  ;; TODO shouldn't this be [:span.empty ...] ??? Are both of those needed or should they be collapsed
  (testing "link to real block"
    (is (= [:span "foo " [:a {:href "bar"} "bar"] " baz " [:a.external {:href "yuck"} "ugh"]]
           (block-hiccup (fake-block "foo [[bar]] baz [ugh](yuck)") fake-block-map)))
    (is (= [:span "foo " [:a {:href "bar"} "bar"] " and " [:a {:href "baz"} "baz"]]
           (block-hiccup (fake-block "foo [[bar]] and [[baz]]") fake-block-map)
         )))

  (testing "link to missing block"
    (is (= [:span "foo " [:span.empty "zorch"] " baz " [:a.external {:href "yuck"} "ugh"]]
           (block-hiccup (fake-block "foo [[zorch]] baz [ugh](yuck)") fake-block-map)))
    )

  (is (= [:span "foo " [:a.external {:href "yuck"} "ugh"] " baz " [:a {:href "bar"} "bar"]]
         (block-hiccup (fake-block "foo [ugh](yuck) baz [[bar]]") fake-block-map)
         ))
  (is (= [:span "foo " [:a.external {:href "yuck"} "ugh"] " baz " [:a.external {:href "zippy"} "yow"]]

         (block-hiccup (fake-block "foo [ugh](yuck) baz [yow](zippy)") {})))

  )

(deftest blockquote-gen-test
  (testing "simple blockquote"
    (is (= [:blockquote "Call me Ishmael."]
           (block-hiccup (fake-block "> Call me Ishmael.") {}))))
  (testing "multiline blockquote"
    (is (= [:blockquote "I see the Four-fold Man, The Humanity in deadly sleep
And its fallen Emanation, the Spectre and its cruel Shadow."]
           (block-hiccup (fake-block "> I see the Four-fold Man, The Humanity in deadly sleep
And its fallen Emanation, the Spectre and its cruel Shadow.") {}))))

  (testing "blockquote with embedded markup"
    (is
     (= [:blockquote
          "A: Well, " [:b "meditation is dealing with purpose itself"] ". It is not that meditation is for something, but it is dealing with the aim."]
        (block-hiccup (fake-block "> A: Well, **meditation is dealing with purpose itself**. It is not that meditation is for something, but it is dealing with the aim.") {})))))

(deftest code-block-test
  (testing "codeblock htmlgen"
    (is (= [:code.codeblock "This is code\n and so is this."]
           (block-hiccup (fake-block  "```javascript\nThis is code
 and so is this.```") {})))))

(deftest markup-in-page-names-test
  (is (= [:a {:href "__foo__"} [:i "foo"]] 
         (block-hiccup (fake-block "[[__foo__]]")
                       (fake-block-map+
                        (prep-block
                         {:title "__foo__"
                          :include? true
                          :page? true
                          :content "eh"}))))))

(deftest italic-link-bug
  (testing "link inside italics"
    (is (= [:span
            "  – Wiliam S. Burroughs,  "
            [:i [:a.external {:href "http://books.google.com/books?id=Vg-ns2orYBMC&pg=PA479"} "The Western Lands"]]
            "."]
           (block-hiccup (fake-block 
            "  – Wiliam S. Burroughs,  __[The Western Lands](http://books.google.com/books?id=Vg-ns2orYBMC&pg=PA479)__.") {}))))
  (testing "italic inside link"
    (is (= [:span
            "  – Wiliam S. Burroughs,  "
            [:a.external {:href "http://books.google.com/books?id=Vg-ns2orYBMC&pg=PA479"} [:i "The Western Lands"]]
            "."]
           (block-hiccup (fake-block
            "  – Wiliam S. Burroughs,  [__The Western Lands__](http://books.google.com/books?id=Vg-ns2orYBMC&pg=PA479).") {})))))

(deftest youtube-id-test
  (is (= "rrstrOrJxOc"
         (get-youtube-id "pretty good video https://www.youtube.com/watch?v=rrstrOrJxOc")))
  (is (= "dO6v_tZtyu0"
         (get-youtube-id "– The Who, [__The Seeker__](https://youtu.be/dO6v_tZtyu0)")))
  (is (= "PwuckTkE7T4"
         (get-youtube-id "https://youtu.be/PwuckTkE7T4")))
  (is (= "-Jq0lohh_5U"
         (get-youtube-id "LA Open School {{[[video]]: https://youtu.be/-Jq0lohh_5U}}")))
  (is (= "0nU4EnB6wiE"
         (get-youtube-id "Dennett et al debate free will https://www.youtube.com/watch?v=0nU4EnB6wiE&feature=youtu.be")))
  (is (nil? (get-youtube-id "Not a youtube https://www.foo.com"))))

(deftest multiline-alias-test
  (is (= [:a.external
          {:href "https://faculty.washington.edu/lynnhank/GouldLewontin.pdf"}
          "The Spandrels of San Marco and the Panglossian Paradigm:\nA Critique of the Adaptationist Programme"]
         (ele->hiccup
          [:alias
           "[The Spandrels of San Marco and the Panglossian Paradigm:\nA Critique of the Adaptationist Programme](https://faculty.washington.edu/lynnhank/GouldLewontin.pdf)"] {}))))

(deftest internal-external-test
  (is (= [:span
          "Blah blah "
          [:a {:href "What-Motivated-Rescuers-During-the-Holocaust"} "finished coherent essay"]
          " or "
          [:a.external {:href "http://link"} "normal"]])
      (block-content->hiccup "Blah blah [finished coherent essay]([[What Motivated Rescuers During the Holocaust?]])")))

(deftest hiccup-render-test
  (= [:table.table
      [:tr [:th "The Magician"] [:td "concentration without effort"] [:td "pure act"]]
      [:tr [:th "The High Priestess"] [:td "vigilant inner silence"] [:td "reflection of pure act"]]
      ]
     (block-content->hiccup "[:table \n[:tr [:th \"The Magician\"] [:td \"concentration without effort\"] [:td \"pure act\"]]\n [:tr [:th \"The High Priestess\"] [:td \"vigilant inner silence\"] [:td \"reflection of pure act\"]]]")))
