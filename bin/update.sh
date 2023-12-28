# MacOS only
# TODO relies on there being an open Logseq window THAT IS OPEN to the right graph, which is bad
# TODO also seems to not really force the database out.  

# Run before generate, forces a C-x C-s (save graph command) to Logseq
# see also https://github.com/logseq/nbb-logseq/issues/1

# Fucking hell, MacOS Ventura (13.0) breaks this so it can no longer work.
# Possible solution:
# 1) disable SI https://developer.apple.com/documentation/security/disabling_and_enabling_system_integrity_protection and
# 2)  add osascript to the System Pref > Privacy & Security > Automation pane
#     (which will now have a + icon)

osascript bin/update.script


