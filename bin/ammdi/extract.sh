# Not used normally, it's done from Clojure as part of the build, see goddinpotty.import.logseq/nbb-query

nbb-logseq resources/nbb-query.cljs ammdi  "[:find (pull ?b [*]) :where [?b :block/uuid _]]" > ammdi.edn

