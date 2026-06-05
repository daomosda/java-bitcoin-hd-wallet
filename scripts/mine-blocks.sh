# mine-blocks.sh

#!/bin/bash
# Mine N blocks to ADDRESS on regtest

BLOCKS=${1:5}
ADDRESS=${2:-"bcrt1qq5z0f2sl8s5zvt8cswfzp8fzhdedz379qfhu56"}
CLI="bitcoin-cli -regtest"

if [ -z "$ADDRESS" ]; then
    echo "Usage: ./mine-blocks.sh <num_blocks> <address>"
    exit 1
fi

echo "Mining $BLOCKS block(s) to $ADDRESS..."
$CLI generatetoaddress $BLOCKS $ADDRESS
echo "Done. Current height: $($CLI getblockcount)"
EOF

chmod +x scripts/mine-blocks.sh