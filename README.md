# Java Bitcoin HD Wallet Engine

A Bitcoin HD wallet and transaction engine implemented in Java, built from protocol fundamentals with no wallet framework dependencies. Implements BIP32/BIP39 key derivation, SegWit transaction construction, PSBT creation and signing, Bitcoin Core descriptor wallet integration, UTXO chain indexing with reorg detection, and Lightning-oriented payment channel tooling.

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for system design and protocol coverage, and [ROADMAP.md](ROADMAP.md) for planned features.

## What Makes This Different

Most Java Bitcoin projects use BitcoinJ for everything — address generation, signing, and serialization handled by the library. This project implements the protocol layer directly:

- The PSBT parser reads raw BIP174 binary — no library
- The BIP143 sighash is constructed byte-by-byte per the spec
- secp256k1 scalar multiplication is called directly via BouncyCastle
- BIP32 child key derivation uses HMAC-SHA512 directly
- Bech32 encoding implements the BCH checksum from the BIP173 spec
- The UTXO indexer tracks spent/unspent state with reorg rollback


## Quick Start

### Prerequisites

- Java 21+
- Bitcoin Core (with regtest enabled)
- Maven

### Configure Bitcoin Core (regtest)

Create `~/.bitcoinlike/bitcoin/regtest/bitcoin.conf`:

```bash
mkdir -p ~/.bitcoinlike/bitcoin/regtest
nano ~/.bitcoinlike/bitcoin/regtest/bitcoin.conf
```

```ini
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

### Start Bitcoin Core

```bash
bitcoind -regtest -daemon -conf=~/.bitcoinlike/bitcoin/regtest/bitcoin.conf

# Verify
bitcoin-cli -regtest -datadir=~/.bitcoinlike/bitcoin getblockchaininfo
```

### Application Configuration

Edit `src/main/resources/bitcoin.conf`:

```ini
network=regtest
rpcuser=<your-rpc-user>
rpcpassword=<your-rpc-password>
rpcallowip=127.0.0.1
rpcmainport=8332
rpctestport=18332
rpcregport=18443
btc.network=regtest
watchonly.wallet.name=daowa_watchonly
```

### Build and Run

```bash
git clone https://github.com/daomosda/java-bitcoin-hd-wallet
cd java-bitcoin-hd-wallet
mvn compile
mvn exec:java -Dexec.mainClass="com.bitcoin.hdwallet.entrypoint.BTCHDWallet"
```

The app launches as one of three node types: standalone, merchant, or customer, each with a tailored CLI menu. For example, a merchant node presents:

```
1)  Show wallet info
2)  Show fee rate estimate
3)  Send coins
4)  Mine blocks (regtest)
5)  Merchant: receive payment
6)  Merchant: receive payment (API/custom capacity)
7)  Sync UTXOs now
8)  Sync chain now
9)  Consolidate change UTXOs
0)  Exit application
```

## License

MIT License — see [LICENSE](LICENSE)
