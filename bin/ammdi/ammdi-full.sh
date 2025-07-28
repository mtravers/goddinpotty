set -e  # necessary for ammdi-check to work, UGH

bin/ammdi-generate.sh
bin/ammdi-check.sh
bin/ammdi-serve.sh

