if [[ $( grep -e '#Private' -i -r /opt/mt/repos/hyperphor-git/ammdi ) ]]; then
    echo "Privacy Breach"
    exit 1			# NOTE requires set -e be done above to really abort
else
    echo "Privacy OK"
fi
	   
