# Regtest Setup Guide

mkdir -p /home/conaldes/.bitcoinlike/bitcoin/regtest
nano /home/conaldes/.bitcoinlike/bitcoin/regtest/bitcoin.conf

```
[regtest]
regtest=1
datadir=/home/conaldes/.bitcoinlike/bitcoin
server=1
rpcuser=olamosda
rpcpassword=cona.btc-Dao_5MoS7
rpcallowip=127.0.0.1
rpcport=18443

txindex=1
descriptors=1
fallbackfee=0.0002
```

### Regtest Setup

```bash
# Start bitcoind in regtest

bitcoind -regtest -daemon -conf=/home/conaldes/.bitcoinlike/bitcoin/regtest/bitcoin.conf

# Verify

bitcoin-cli -regtest -datadir=/home/conaldes/.bitcoinlike/bitcoin getblockchaininfo
```
# Stop bitcoind in regtest

bitcoin-cli -regtest -conf=/home/conaldes/.bitcoinlike/bitcoin/regtest/bitcoin.conf stop


