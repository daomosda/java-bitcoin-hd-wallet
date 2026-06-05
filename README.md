\# Java Bitcoin HD Wallet Engine



A Bitcoin HD wallet and transaction engine implemented in Java — built

from protocol fundamentals with no wallet framework dependencies.



Implements BIP32/BIP39 key derivation, SegWit transaction construction,

PSBT creation and signing, Bitcoin Core descriptor wallet integration,

UTXO chain indexing with reorg detection, and Lightning-oriented payment

channel tooling.



\---



\## Features



\### Wallet

\- BIP39 mnemonic generation and seed derivation (PBKDF2-HMAC-SHA512)

\- BIP32 hierarchical deterministic key derivation

\- BIP84 native SegWit (P2WPKH) address generation

\- Address pool management with lookahead gap limit

\- Used/unused address tracking with SQLite persistence

\- Watch-only descriptor wallet import into Bitcoin Core



\### Transactions

\- PSBT (BIP174) binary parser and serializer — written from scratch

\- Manual BIP143 SegWit sighash computation

\- RFC6979 deterministic ECDSA signing via secp256k1

\- Transaction construction, fee estimation, change address handling

\- PSBT finalization and raw transaction broadcast



\### Chain Indexing

\- Full block sync from Bitcoin Core via JSON-RPC

\- Five-table SQLite index: blocks, transactions, inputs, outputs, UTXOs

\- Reorg detection and atomic rollback

\- UTXO set maintenance (create, spend, restore on reorg)



\### Cryptography (implemented from scratch)

\- secp256k1 ECDH shared secret computation

\- BIP340 Schnorr-ready point arithmetic

\- HKDF key derivation (RFC 5869)

\- Bech32 encoding/decoding (BIP173)

\- Base58Check encoding/decoding



\### Lightning (payment channel tooling)

\- HTLC state machine (add, fulfill, settle, fail)

\- Invoice generation with preimage/hash pairs

\- Sphinx onion packet construction

\- P2P peer messaging layer

\- Merchant and customer payment handler flows



\### Infrastructure

\- Bitcoin Core JSON-RPC client (regtest / testnet / mainnet)

\- Regtest-focused tooling for rapid testing

\- SQLite database with WAL mode and per-thread connections

\- Blockchain reorg detection and recovery

\- Descriptor import into watch-only wallet

\- P2P networking layer (P2PServer/P2PClient/NetworkManager)

\- APIs for merchant and customer nodes



\---



\## Architecture



\*\*Wallet Layer\*\*



\- `Bip32HDWallet` – master seed and BIP32 child key derivation

\- `HdAddressManager` – receive/change address pools with lookahead

\- `HDKeyRepository` – persistent storage of addresses and metadata



\*\*Transaction Layer\*\*



\- `Psbt` – BIP174 PSBT parser/serializer

\- `OwnSigner` – BIP143 SegWit sighash + RFC6979 ECDSA signing

\- `TxData`, `TxInputData`, `TxOutputData` – transaction model types



\*\*Chain Layer\*\*



\- `ChainIndexer` – Initial sync from genesis (or from wherever we left off)

&#x20;\* Incremental sync on new blocks

&#x20;\* Reorg detection and rollback

&#x20;\* Populating all five tables atomically per block

\- `ChainIndexRepository` / `ChainDataWriter` – on-disk UTXO/chain index

\- `ReorgDetector` – detect and handle chain reorganizations



\*\*Crypto Layer\*\*



\- `Secp256k1` – ECDSA/EC operations

\- `Bech32`, `Base58`, `Crypto`, `Hkdf` – encoding and key derivation helpers



\*\*Lightning / P2P Layer\*\*



\- `LightningService`, `MerchantPaymentHandler`, `CustomerPaymentHandler`

\- `HTLC`, `Invoice` models

\- `NetworkManager`, `P2PServer`, `P2PClient`, `Peer`



\## Implemented from scratch:



BIP32 HD wallet derivation

Descriptor wallet integration

Descriptor checksum handling

SegWit transaction construction

BIP143 sighash computation

Witness serialization

PSBT parsing and updating

PSBT signing flow

RFC6979 deterministic ECDSA

UTXO synchronization engine

SQLite-backed chain index

Bitcoin Core RPC client

Reorg detection logic

Watch-only wallet infrastructure

Change-address tracking

Lightning payment channel

\---



\## Protocols Implemented



| Protocol / Spec              | Reference       | Status|

|------------------------------|-----------------|-------|

| Mnemonic phrases             | BIP39           | ✅   |

| HD key derivation            | BIP32           | ✅   |

| SegWit key derivation        | BIP84           | ✅   |

| Native SegWit addresses      | BIP173 (Bech32) | ✅   |

| SegWit transaction signing   | BIP143          | ✅   |

| Partially Signed Bitcoin Tx  | BIP174 (PSBT)   | ✅   |

| Descriptor wallets           | BIP380/381/382  | ✅   |

| Deterministic ECDSA          | RFC6979         | ✅   |

| Onion routing                | BOLT 4          | ✅   |

\---



\## Quick Start



\### Prerequisites



\- Java 21+

\- Bitcoin Core (with regtest enabled)

\- Maven or Gradle



\### Maven



```

<?xml version="1.0" encoding="UTF-8"?>

<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

&#x20;   <modelVersion>4.0.0</modelVersion>

&#x20;   <groupId>com.bitcoin.hdwallet</groupId>

&#x20;   <artifactId>BTCHDWallet</artifactId>

&#x20;   <version>1.0-SNAPSHOT</version>

&#x20;   <packaging>jar</packaging>

&#x20;   <dependencies>

&#x20;       <dependency>

&#x20;           <groupId>org.bouncycastle</groupId>

&#x20;           <artifactId>bcprov-jdk18on</artifactId>

&#x20;           <version>1.83</version>

&#x20;           <type>jar</type>

&#x20;       </dependency>

&#x20;       <dependency>

&#x20;           <groupId>com.fasterxml.jackson.core</groupId>

&#x20;           <artifactId>jackson-databind</artifactId>

&#x20;           <version>2.16.1</version>

&#x20;           <type>jar</type>

&#x20;       </dependency>

&#x20;       <dependency>

&#x20;           <groupId>org.json</groupId>

&#x20;           <artifactId>json</artifactId>

&#x20;           <version>20180130</version>

&#x20;           <type>jar</type>

&#x20;       </dependency>

&#x20;       <dependency>

&#x20;           <groupId>org.xerial</groupId>

&#x20;           <artifactId>sqlite-jdbc</artifactId>

&#x20;           <version>3.49.1.0</version>

&#x20;           <type>jar</type>

&#x20;       </dependency>

&#x20;   </dependencies>

&#x20;   <properties>

&#x20;       <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

&#x20;       <maven.compiler.release>25</maven.compiler.release>

&#x20;       <exec.mainClass>com.bitcoin.hdwallet.entrypoint.BTCHDWallet</exec.mainClass>

&#x20;   </properties>

</project>

```



\### Configure Bitcoin Core (regtest) 



This is for Ubuntu 20.04.6 LTS on Windows 11; 'conaldes' is my users name and '.bitcoinlike' a directory on it.

Create `\~/.bitcoinlike/bitcoin/regtest/bitcoin.conf`:



mkdir -p /home/conaldes/.bitcoinlike/bitcoin/regtest

nano /home/conaldes/.bitcoinlike/bitcoin/regtest/bitcoin.conf



```

\[regtest]

regtest=1

datadir=/home/conaldes/.bitcoinlike/bitcoin

server=1

rpcuser=olamosda

rpcpassword=cona.btc-Dao\_5MoS7

rpcallowip=127.0.0.1

rpcport=18443



txindex=1

descriptors=1



fallbackfee=0.0002

```



\### Regtest Setup



```bash

\# Start bitcoind in regtest



bitcoind -regtest -daemon -conf=/home/conaldes/.bitcoinlike/bitcoin/regtest/bitcoin.conf





\# Verify

bitcoin-cli -regtest -datadir=/home/conaldes/.bitcoinlike/bitcoin getblockchaininfo

```

\### Configuration



Edit `src/main/resources/bitcoin.conf`:



```properties

network=regtest

rpcuser=olamosda

rpcpassword=cona.btc-Dao\_5MoS7

rpcallowip=127.0.0.1

rpcmainport=8332

rpctestport=18332

rpcregport=18443

btc.network=regtest

watchonly.wallet.name=daowa\_watchonly

```



\### Build and Run



```bash

git clone https://github.com/CONALDES/java-bitcoin-hd-wallet-engine

cd java-bitcoin-hd-wallet-engine

mvn compile

mvn exec:java -Dexec.mainClass="com.bitcoin.hdwallet.entrypoint.BTCHDWallet"

```

You should see a CLI menu:



```text

1\)  Show wallet info

2\)  Show fee rate estimate

3\)  Send coins

4\)  Mine blocks (regtest)

5\)  Merchant: receive payment

6\)  Merchant: receive payment (API/custom capacity)

7\)  Sync UTXOs now          

8\)  Sync chain now

9\)  Consolidate change UTXOs

0\)  Exit application

Select option: 



or 



1\)  Show wallet info

2\)  Show fee rate estimate

3\)  Send coins

4\)  Mine blocks (regtest)

5\)  Customer make payment

6\)  Sync UTXOs now        

7\)  Sync chain now 

8\)  Consolidate change UTXOs

0\)  Exit application

Select option:



or



1\)  Show wallet info

2\)  Show fee rate estimate

3\)  Send coins

4\)  Mine blocks (regtest)

5\)  Sync UTXOs now        

6\)  Sync chain now 

7\)  Consolidate change UTXOs

0\)  Exit application

Select option:

```



\---



\## What Makes This Different From Tutorial Wallets



Most Java Bitcoin projects use BitcoinJ for everything — address

generation, signing, and serialization are all handled by the library.



This project implements the protocol layer directly:



\- The PSBT parser reads raw BIP174 binary — no library

\- The BIP143 sighash is constructed byte-by-byte per the spec

\- secp256k1 scalar multiplication is called directly via BouncyCastle

\- BIP32 child key derivation uses HMAC-SHA512 directly

\- Bech32 encoding implements the BCH checksum from the BIP173 spec

\- The UTXO indexer tracks spent/unspent state with reorg rollback



\---



\## Roadmap



\- \[ ] Taproot (P2TR) address support

\- \[ ] BIP340 Schnorr signatures

\- \[ ] Miniscript policy compilation

\- \[ ] Compact block filters (BIP157/158)

\- \[ ] Hardware wallet integration (HWI)

\- \[ ] Tapscript spending conditions



\---



\## License



MIT License — see \[LICENSE](LICENSE)



\---



\## Author



Built as a demonstration of Bitcoin protocol-level engineering in Java.

Covers HD wallet derivation through PSBT signing through chain indexing.



\---



