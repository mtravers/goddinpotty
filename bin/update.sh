# Run before any Logseq generate, quits Logseq to force graph out
# TODO something better than runs from nbb-logseq and doesn't quit
# TODO should relaunch Logseq! Duh. Still annoying but less so. How do you do that in Applescript?
osascript bin/update.script
# TODO this doesn't work either, probably needs to wait for old one to quit, not sure how to do that.
open /Applications/Logseq.app	# tried to do this with applscript, doesn't really work

