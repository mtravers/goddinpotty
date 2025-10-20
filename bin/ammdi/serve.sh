# Picks a random port (what I want is an UNUSED port, but that's harder)
AMMDI_PORT=$(shuf -i 1024-65535 -n 1)
echo Serving on $AMMDI_PORT
bin/serve.sh $AMMDI_PORT /opt/mt/repos/hyperphor-git/ammdi
