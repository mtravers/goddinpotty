lein uberjar
cp target/goddinpotty-2.0.1-standalone.jar app
rm -rf RoamAway.app
jpackage --name RoamAway --input app --main-jar goddinpotty-2.0.1-standalone.jar  --main-class goddinpotty.convert.roam_logseq_ui # for dev --type app-image

