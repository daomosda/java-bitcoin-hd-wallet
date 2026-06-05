## Architecture

### Wallet Layer

- `Bip32HDWallet` – master seed and BIP32 child key derivation
- `HdAddressManager` – receive/change address pools with lookahead
- `HDKeyRepository` – persistent storage of addresses and metadata

### Transaction Layer

- `Psbt` – BIP174 PSBT parser/serializer
- `OwnSigner` – BIP143 SegWit sighash + RFC6979 ECDSA signing
- `TxData`, `TxInputData`, `TxOutputData` – transaction model types

### Chain Layer

- `ChainIndexer` – Initial sync from genesis (or from wherever we left off)
 * Incremental sync on new blocks
 * Reorg detection and rollback
 * Populating all five tables atomically per block
- `ChainIndexRepository` / `ChainDataWriter` – on-disk UTXO/chain index
- `ReorgDetector` – detect and handle chain reorganizations

### Crypto Layer

- `Secp256k1` – ECDSA/EC operations
- `Bech32`, `Base58`, `Crypto`, `Hkdf` – encoding and key derivation helpers

### Lightning / P2P Layer

- `LightningService`, `MerchantPaymentHandler`, `CustomerPaymentHandler`
- `HTLC`, `Invoice` models
- `NetworkManager`, `P2PServer`, `P2PClient`, `Peer`

### Infrastructure Layer
- `BitcoinRpcClient.java` — JSON-RPC over HTTP
- `ChainDatabase.java` — WAL mode, ThreadLocal connections
