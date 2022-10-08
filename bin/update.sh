# Run before any Logseq generate, quits Logseq to force graph out
# TODO something better than runs from nbb-logseq and doesn't quit
# TODO turned off, it doesn't even work (that is, not all changes get saved to the db, would need to do a reindex I guess which is slow and I don't know if its applescriptable.)
# osascript bin/update.script
# TODO this doesn't work either, probably needs to wait for old one to quit, not sure how to do that.
# open /Applications/Logseq.app	# tried to do this with applscript, doesn't really work

echo "You need to hit C-x C-s (save current graph to disk) in Logseq"
# TODO see https://github.com/logseq/nbb-logseq/issues/1

