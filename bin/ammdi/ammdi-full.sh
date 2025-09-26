set -e  # necessary for ammdi-check to work, UGH

bin/ammdi/ammdi-generate.sh
bin/ammdi/ammdi-check.sh
bin/ammdi/ammdi-serve.sh

