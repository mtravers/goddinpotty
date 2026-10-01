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
        block (assoc (prep 2 "Hello world #blyg") :parent 1)
        bm {1 pg 2 block}
        output-dir (config/config :output-dir)]

    (testing "first publish creates v1, strips the tag"
      (let [state (blygger/publish! bm output-dir)
            entry (get (:items state) 2)]
        (is (= 1 (:version entry)))
        (is (= :fragment (:kind entry)))
        (is (= 26 (count (:blyg-id entry))))
        (is (not (re-find #"blyg" (:content-md entry))))
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
      (let [bm2 (assoc bm 2 (assoc (prep 2 "Hello there #blyg") :parent 1))
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

(deftest thread-test
  (let [pg (fake-page 20 "Thread Page")
        child1 (assoc (prep 22 "First point") :parent 21)
        child2 (assoc (prep 23 "Second point") :parent 21)
        parent (assoc (prep 21 "My points #blyg" :children [22 23]) :parent 20)
        bm {20 pg 21 parent 22 child1 23 child2}
        output-dir (config/config :output-dir)]

    (testing "a tagged block with children becomes a thread; children are promoted to their own fragments"
      (let [state (blygger/publish! bm output-dir)
            items (:items state)
            thread-entry (get items 21)
            child1-entry (get items 22)
            child2-entry (get items 23)]
        (is (= :thread (:kind thread-entry)))
        (is (= :fragment (:kind child1-entry)))
        (is (= :fragment (:kind child2-entry)))
        (is (re-find (re-pattern (str "!\\[\\[" (:blyg-id child1-entry) "\\]\\]")) (:content-md thread-entry)))
        (is (re-find (re-pattern (str "!\\[\\[" (:blyg-id child2-entry) "\\]\\]")) (:content-md thread-entry)))
        (is (= [{:id (:blyg-id child1-entry) :version (:version child1-entry)}
                {:id (:blyg-id child2-entry) :version (:version child2-entry)}]
               (:transclusions thread-entry)))
        (is (re-find #"blyg-transclusion" (:content-html thread-entry)))
        (is (re-find (re-pattern (str "data-blyg-id=\"" (:blyg-id child1-entry) "\"")) (:content-html thread-entry)))
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id child1-entry) ".json")))
        (is (fs/exists? (str output-dir "/blyg/f/" (:blyg-id child1-entry) "/index.html")))

        ;; Only the outermost (thread) block carries "From [Page]"
        ;; attribution -- a promoted child's own content (both standalone
        ;; and what gets baked into the thread's transclusion) must not
        ;; repeat it.
        (is (re-find #"From \[Thread Page\]" (:content-md thread-entry)))
        (is (not (re-find #"From \[" (:content-md child1-entry))))
        (is (not (re-find #"source-page" (:content-html child1-entry))))
        (is (= 1 (count (re-seq #"source-page" (:content-html thread-entry))))
            "exactly one attribution (the thread's own) -- not a second one leaking in from the transcluded child")

        ;; Children are real, independently fetchable items (json/permalink
        ;; above) but must NOT also appear as their own card in the
        ;; human-facing archive page or feed.xml -- that's the content
        ;; showing up twice (once standalone, once transcluded in the
        ;; thread) bug this guards against.
        (is (true? (:thread-child? child1-entry)))
        (is (true? (:thread-child? child2-entry)))
        (is (not (:thread-child? thread-entry)))
        (let [archive-html (slurp (str output-dir "/blyg/index.html"))
              feed-xml (slurp (str output-dir "/blyg/feed.xml"))]
          ;; present once, inside the thread's transclusion blockquote --
          ;; not a second time as a standalone permalink/card
          (is (= 1 (count (re-seq (re-pattern (:blyg-id child1-entry)) archive-html))))
          (is (= 1 (count (re-seq (re-pattern (:blyg-id child2-entry)) archive-html))))
          ;; The child's id legitimately appears inside the thread's own feed
          ;; entry (baked into its transcluded content_html description) --
          ;; what must NOT exist is a <blyg:id> for the child, which is only
          ;; emitted for an item's *own* feed entry (feed-item-xml).
          (is (not (re-find (re-pattern (str "<blyg:id>" (:blyg-id child1-entry) "</blyg:id>")) feed-xml)))
          (is (not (re-find (re-pattern (str "<blyg:id>" (:blyg-id child2-entry) "</blyg:id>")) feed-xml))))))

    (testing "republishing unchanged is a no-op for the thread and its children"
      (let [before (blygger/load-state (config/config :blygger :state-file))
            after (blygger/publish! bm output-dir)]
        (is (= (:items before) (:items after)))))

    (testing "editing a child's content bumps the child's version but does NOT
              change the thread's already-baked snapshot (protocol §10.4) --
              a thread's directives don't carry a version (§10.1), so the
              thread's own content-md -- and thus whether it re-bumps -- only
              changes when the directive list itself changes"
      (let [bm2 (assoc bm 22 (assoc (prep 22 "First point, revised") :parent 21))
            before (blygger/load-state (config/config :blygger :state-file))
            after (blygger/publish! bm2 output-dir)
            thread-before (get-in before [:items 21])
            thread-after (get-in after [:items 21])
            child1-after (get-in after [:items 22])]
        (is (= 2 (:version child1-after)))
        (is (= thread-before thread-after))))

    (testing "removing a child from the parent's children changes the thread's
              directive list, which bumps the thread's own version"
      (let [bm3 (assoc bm 21 (assoc (prep 21 "My points #blyg" :children [22]) :parent 20))
            state (blygger/publish! bm3 output-dir)
            thread-entry (get (:items state) 21)]
        (is (= 2 (:version thread-entry)))
        (is (not (re-find #"Second point" (:content-md thread-entry))))))

    (testing "a tagged block with no (surviving) children stays a plain fragment"
      (let [leaf (assoc (prep 24 "Leaf #blyg") :parent 20)
            bm4 (assoc bm 24 leaf)
            state (blygger/publish! bm4 output-dir)
            entry (get (:items state) 24)]
        (is (= :fragment (:kind entry)))))))

(deftest thread-child-becomes-its-own-thread-test
  (testing "a block that was a promoted child in an earlier run, then later
            becomes a #blyg thread in its own right, must not keep a stale
            :thread-child? true -- it has to come back visible on the
            archive page. Real bug, found from a real report: a block was
            once a child, got restructured into its own #blyg-pin thread,
            and silently vanished from the archive despite being correctly
            published and pinned."
    (let [pg (fake-page 60 "Flip Page")
          output-dir (config/config :output-dir)
          ;; Run 1: 61 is a plain child of thread 62 -- gets :thread-child? true.
          child61 (assoc (prep 61 "Once a child") :parent 62)
          parent62 (assoc (prep 62 "Parent #blyg" :children [61]) :parent 60)
          bm1 {60 pg 61 child61 62 parent62}
          _ (blygger/publish! bm1 output-dir)
          ;; Run 2: 61 is restructured into its own thread, no longer under 62.
          grandchild63 (assoc (prep 63 "A point") :parent 61)
          child61-now-thread (assoc (prep 61 "Now its own thread #blyg" :children [63]) :parent 60)
          parent62-no-kids (assoc (prep 62 "Parent #blyg") :parent 60)
          bm2 {60 pg 61 child61-now-thread 62 parent62-no-kids 63 grandchild63}
          state (blygger/publish! bm2 output-dir)
          entry (get (:items state) 61)]
      (is (= :thread (:kind entry)))
      (is (not (:thread-child? entry)) "stale flag from run 1 must be cleared")
      (is (fs/exists? (str output-dir "/blyg/index.html")))
      (let [archive-html (slurp (str output-dir "/blyg/index.html"))]
        (is (re-find (re-pattern (:blyg-id entry)) archive-html)
            "now-a-thread block must appear in the visible archive")))))

(deftest thread-privacy-test
  (testing "a child privatized the normal nested-tag way (a #Private child
            beneath it, bd/tagged?'s 'contained' convention) is excluded from
            the thread individually -- rather than cascading up and
            excluding the whole thread, which it would if the tag were on
            the child itself (tagged?'s containment check looks at *direct*
            children, so the tag has to be one level deeper than the point
            it privatizes, same as anywhere else in this codebase)"
    (let [pg (fake-page 40 "Thread Privacy Page")
          private-page (fake-page 100 "Private")
          private-tag-block (assoc (prep 44 "#Private") :parent 42 :refs #{100})
          private-child (assoc (prep 42 "Secret point" :children [44]) :parent 41)
          public-child (assoc (prep 43 "Public point") :parent 41)
          parent (assoc (prep 41 "My points #blyg" :children [42 43]) :parent 40)
          bm {40 pg 41 parent 42 private-child 43 public-child 44 private-tag-block 100 private-page}
          output-dir (config/config :output-dir)
          state (blygger/publish! bm output-dir)
          items (:items state)
          thread-entry (get items 41)]
      (is (= :thread (:kind thread-entry)))
      (is (nil? (get items 42)))
      (is (some? (get items 43)))
      (is (= 1 (count (:transclusions thread-entry))))
      (is (= (:blyg-id (get items 43)) (:id (first (:transclusions thread-entry))))))))

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

(deftest pin-test
  (let [pg (fake-page 50 "Pin Page")
        output-dir (config/config :output-dir)]

    (testing "#blyg-pin pins the current latest version"
      (let [block (assoc (prep 51 "Hello world #blyg #blyg-pin") :parent 50)
            bm {50 pg 51 block}
            state (blygger/publish! bm output-dir)
            entry (get (:items state) 51)]
        (is (not (re-find #"pin" (:content-md entry))) "pin tag stripped like the main tag")
        (is (contains? (:pins entry) 1))
        (is (= (:content-md entry) (:content-md (get-in entry [:pins 1]))))
        (is (true? (:pinned (first (:changelog entry)))))
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id entry) "/v1.json")))))

    (testing "republishing unchanged is idempotent -- no duplicate/changed pin"
      (let [block (assoc (prep 51 "Hello world #blyg #blyg-pin") :parent 50)
            bm {50 pg 51 block}
            before (blygger/load-state (config/config :blygger :state-file))
            after (blygger/publish! bm output-dir)]
        (is (= (get-in before [:items 51]) (get-in after [:items 51])))))

    (testing "editing content after pinning bumps the version and pins v1 stays frozen;
              leaving the tag on pins the new version too (documented behavior,
              not a one-shot -- see design/blygger.md Stage 1.6)"
      (let [block2 (assoc (prep 51 "Hello there #blyg #blyg-pin") :parent 50)
            bm2 {50 pg 51 block2}
            state (blygger/publish! bm2 output-dir)
            entry (get (:items state) 51)]
        (is (= 2 (:version entry)))
        (is (contains? (:pins entry) 1))
        (is (contains? (:pins entry) 2))
        (is (not= (get-in entry [:pins 1 :content-md]) (get-in entry [:pins 2 :content-md])))
        (is (fs/exists? (str output-dir "/blyg/items/" (:blyg-id entry) "/v2.json")))))

    (testing "#blyg-pin alone (no separate #blyg) still publishes AND pins --
              the pin tag implies the main tag, so one tag does both"
      (let [block (assoc (prep 52 "Implied publish #blyg-pin") :parent 50)
            bm {50 pg 52 block}
            state (blygger/publish! bm output-dir)
            entry (get (:items state) 52)]
        (is (= :fragment (:kind entry)))
        (is (not (re-find #"pin" (:content-md entry))))
        (is (contains? (:pins entry) 1))))

    (testing "a thread's pin captures its baked transclusions too"
      (let [child (assoc (prep 54 "A point") :parent 53)
            parent (assoc (prep 53 "Points #blyg #blyg-pin" :children [54]) :parent 50)
            bm {50 pg 53 parent 54 child}
            state (blygger/publish! bm output-dir)
            thread-entry (get (:items state) 53)]
        (is (= :thread (:kind thread-entry)))
        (is (contains? (:pins thread-entry) (:version thread-entry)))
        (is (seq (get-in thread-entry [:pins (:version thread-entry) :transclusions])))))))

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
