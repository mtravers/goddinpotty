echo "Refreshing #blyg items from $GP_CONFIG (no full rebuild)"
bin/update.sh
lein run -m goddinpotty.core/-main-refresh-blyg $GP_CONFIG
