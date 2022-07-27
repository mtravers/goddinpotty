lein uberjar
cp target/goddinpotty-2.0.1-standalone.jar app
jpackage --name RoamAway --input app --main-jar goddinpotty-2.0.1-standalone.jar  --main-class goddinpotty.convert.roam_logseq --type app-image

