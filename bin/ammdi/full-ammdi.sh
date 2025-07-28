# necessary for check-ammdi to work, UGH
set -e
bin/generate-ammdi.sh
bin/check-ammdi.sh
bin/upload-git.sh
