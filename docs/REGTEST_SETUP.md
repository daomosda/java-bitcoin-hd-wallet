# Regtest Setup Guide

mkdir -p /home/<USER_HOME>/.bitcoinlike/bitcoin/regtest
nano /home/<USER_HOME>/.bitcoinlike/bitcoin/regtest/bitcoin.conf
```
[regtest]
regtest=1
datadir=/home/<USER_HOME>/.bitcoinlike/bitcoin
server=1
rpcuser=<your-rpc-user>
rpcpassword=<your-rpc-password>
rpcallowip=127.0.0.1
rpcport=18443

txindex=1
descriptors=1
fallbackfee=0.0002
```

### Regtest Setup

```bash
# Start bitcoind in regtest

bitcoind -regtest -daemon -conf=/home/<USER_HOME>/.bitcoinlike/bitcoin/regtest/bitcoin.conf

# Verify

bitcoin-cli -regtest -datadir=/home/<USER_HOME>/.bitcoinlike/bitcoin getblockchaininfo
```
# Stop bitcoind in regtest

bitcoin-cli -regtest -conf=/home/<USER_HOME>/.bitcoinlike/bitcoin/regtest/bitcoin.conf stop
