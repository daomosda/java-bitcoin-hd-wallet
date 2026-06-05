# regtest-setup.sh

#!/bin/bash
# Regtest setup script for java-bitcoin-hd-wallet

set -e

WALLET_NAME="btcnode_watchonly"
RPC_USER="olamosda"
RPC_PASS="cona.btc-Dao_5MoS7"
CLI="bitcoin-cli -regtest"

echo "Starting bitcoind..."
bitcoind -regtest -daemon
sleep 2

echo "Creating watch-only descriptor wallet..."
$CLI createwallet "$WALLET_NAME" true true "" false true false

echo "Wallet created: $WALLET_NAME"
echo ""
echo "Now run the wallet application and note your receive address."
echo "Then mine coins with:"
echo "  $CLI generatetoaddress 101 <your-address>"
EOF

chmod +x scripts/regtest-setup.sh