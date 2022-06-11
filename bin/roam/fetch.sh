# Credentials to fetch the Roam graph. 
# This mechanism won't work if you log into Roam with a 3rd party authorization (Google etc).
export ROAM_API_GRAPH=my-graph	   # Roam graph name
export ROAM_API_EMAIL=me@gmail.com # Roam login account name
export ROAM_API_PASSWORD=password  # Roam login account password

# See https://github.com/artpi/roam-research-private-api

# To install the proper version:
# npm i -g git@github.com:dimfeld/roam-research-private-api.git#download-extra-formats --save

echo "Fetching $ROAM_API_GRAPH"
roam-api export --format edn --removezip false ~/Downloads

