(ns goddinpotty.export.blygger-test
  (:require [goddinpotty.export.blygger :as blygger]
            [goddinpotty.parser :as parser]
            [goddinpotty.config :as config]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]))

(defn with-blygger-config
  [f]
  (config/set-config-path! "test/resources/blygger-test-config.edn")
  (fs/delete-dir (config/config :output-dir))
  (let [sf (config/config :blygger :state-file)]
    (when (fs/exists? sf) (fs/delete sf)))
  (f))

(use-fixtures :each with-blygger-config)

;;; Bare-bones fake blocks -- see rendering-test's fake-block-map for precedent.
(defn- prep
  [id content & {:keys [children parent]}]
  {:id id
   :content content
   :parsed (parser/parse-to-ast content)
   :children (or children [])
   :parent parent
   :include? true
   :display? true})

(defn- fake-page
  [id title]
  {:id id :title title :page? true :page-title title :include? true :display? true :children []})

(deftest publish-round-trip-test
  (let [pg (fake-page 1 "Test Page")
        child (assoc (prep 3 "more detail") :parent 2)
        block (assoc (prep 2 "Hello world #blyg" :children [3]) :parent 1)
        bm {1 pg 2 block 3 child}
        output-dir (config/config :output-dir)]

    (testing "first publish creates v1, strips the tag, keeps the child as body"
      (let [state (blygger/publish! bm output-dir)
            entry (get (:items state) 2)]
        (is (= 1 (:version entry)))
        (is (= :fragment (:kind entry)))
        (is (= 26 (count (:blyg-id entry))))
        (is (not (re-find #"blyg" (:content-md entry))))
        (is (re-find #"more detail" (:content-md entry)))
        (is (= 1 (count (:changelog entry))))
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id entry) ".json")))
        (is (fs/exists? (str output-dir "/blyg/blyg.json")))
        (is (fs/exists? (str output-dir "/blyg/items/index.json")))
        (is (fs/exists? (str output-dir "/blyg/feed.xml")))))

    (testing "republishing unchanged content is a no-op (no version bump)"
      (let [before (blygger/load-state (config/config :blygger :state-file))
            after (blygger/publish! bm output-dir)]
        (is (= (get-in before [:items 2]) (get-in after [:items 2])))))

    (testing "content change bumps the version and appends a changelog entry"
      (let [bm2 (assoc bm 2 (assoc (prep 2 "Hello there #blyg" :children [3]) :parent 1))
            state (blygger/publish! bm2 output-dir)
            entry (get (:items state) 2)]
        (is (= 2 (:version entry)))
        (is (= 2 (count (:changelog entry))))))

    (testing "removing the tag without :withdraw? leaves the item alone, just warns"
      (let [bm3 (dissoc bm 2)
            state (blygger/publish! bm3 output-dir)
            entry (get (:items state) 2)]
        (is (= :fragment (:kind entry)))
        (is (= 2 (:version entry)))))

    (testing "removing the tag with :withdraw? true publishes the withdrawal endcap"
      (let [bm3 (dissoc bm 2)
            state (blygger/publish! bm3 output-dir :withdraw? true)
            entry (get (:items state) 2)]
        (is (= :withdrawn (:kind entry)))
        (is (= 3 (:version entry)))
        (is (= "" (:content-md entry)))
        (is (= "" (:content-html entry)))
        ;; still 200 forever: the file is still there, just an empty endcap
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id entry) ".json")))))))

(deftest privacy-gate-test
  (testing "an explicitly tagged #Private block is skipped -- the real privacy boundary"
    (let [private-page (fake-page 100 "Private")
          excluded (assoc (prep 5 "Secret #blyg #Private") :refs #{100})]
      (is (empty? (blygger/blyg-blocks {5 excluded 100 private-page} "blyg")))))

  (testing "#blyg is its own entry point: a block unreachable from any #EntryPoint
            (no page links to it -- :include?/:display? false) still gets published,
            since nothing marks it private"
    (let [orphan (assoc (prep 6 "Orphaned but public #blyg") :include? false :display? false)
          published (blygger/blyg-blocks {6 orphan} "blyg")]
      (is (= 1 (count published)))
      (is (= 6 (:id (first published))))))

  (testing "a #blyg block on a journal/daily-notes page still gets published --
            :excluded? there is database.clj's daily-notes performance-skip flag
            (set when :daily-notes? is false), not a privacy marker"
    (let [journal-block (assoc (prep 7 "Journal thought #blyg") :excluded? true)
          published (blygger/blyg-blocks {7 journal-block} "blyg")]
      (is (= 1 (count published)))
      (is (= 7 (:id (first published)))))))

(deftest source-page-attribution-test
  (testing "a #blyg block on a normal, published content page gets a link back to it"
    (let [pg (fake-page 10 "My Great Page")
          block (assoc (prep 11 "Some thought #blyg") :parent 10)
          bm {10 pg 11 block}
          output-dir (config/config :output-dir)
          state (blygger/publish! bm output-dir)
          entry (get (:items state) 11)]
      (is (re-find #"From \[My Great Page\]" (:content-md entry)))
      (is (re-find (re-pattern (str (config/config :real-base-url) "My-Great-Page"))
                    (:content-md entry)))
      (is (re-find #"source-page" (:content-html entry)))
      (is (re-find #"My Great Page" (:content-html entry)))))

  (testing "a #blyg block on a journal/daily-notes page gets no page attribution --
            journal entries have no meaningful 'page' of their own"
    (let [pg (fake-page 12 "January 1st, 2024")
          block (assoc (prep 13 "Journal thought #blyg") :parent 12)
          bm {12 pg 13 block}
          output-dir (config/config :output-dir)
          state (blygger/publish! bm output-dir)
          entry (get (:items state) 13)]
      (is (not (re-find #"From \[" (:content-md entry))))
      (is (not (re-find #"source-page" (:content-html entry))))))

  (testing "a #blyg block whose page isn't published gets no attribution --
            would be a dead/private link"
    (let [pg (assoc (fake-page 14 "Unpublished Page") :display? false)
          block (assoc (prep 15 "Some thought #blyg") :parent 14)
          bm {14 pg 15 block}
          output-dir (config/config :output-dir)
          state (blygger/publish! bm output-dir)
          entry (get (:items state) 15)]
      (is (not (re-find #"From \[" (:content-md entry))))
      (is (not (re-find #"source-page" (:content-html entry)))))))

(deftest mount-collides-with-tag-page-test
  (testing "the #blyg tag's own backlink page is written extensionless at
            output-dir/blyg by html-generation before blygger/publish! runs --
            the mount directory must win that collision, not blow up"
    (let [block (prep 2 "Hello world #blyg")
          bm {2 block}
          output-dir (config/config :output-dir)]
      (fs/mkdirs output-dir)
      (spit (str output-dir "/blyg") "<html>stale tag page</html>")
      (let [state (blygger/publish! bm output-dir)
            entry (get (:items state) 2)]
        (is (= 1 (:version entry)))
        (is (fs/exists? (str output-dir "/blyg/blyg.json")))
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id entry) ".json")))))))

(deftest dry-run-test
  (testing ":dry-run? true computes state without writing anything"
    (let [block (prep 2 "Dry run #blyg")
          bm {2 block}
          output-dir (config/config :output-dir)
          state (blygger/publish! bm output-dir :dry-run? true)]
      (is (= 1 (get-in state [:items 2 :version])))
      (is (not (fs/exists? (config/config :blygger :state-file))))
      (is (not (fs/exists? (str output-dir "/blyg")))))))
