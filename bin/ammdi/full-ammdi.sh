# necessary for check-ammdi to work, UGH
set -e
bin/ammdi/ammdi-generate.sh
bin/ammdi/ammdi-check.sh
bin/ammdi/upload-git.sh
