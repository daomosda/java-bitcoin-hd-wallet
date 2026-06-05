# SegWit Sighash (BIP143)

## Why Different From Legacy

Legacy sighash had quadratic scaling issues.
BIP143 commits to all inputs/outputs via pre-computed hashes.

## Components

- `hashPrevouts` — SHA256d of all outpoints
- `hashSequence` — SHA256d of all sequences
- `hashOutputs`  — SHA256d of all outputs
- `scriptCode`   — P2PKH form for P2WPKH inputs
- `value`        — input value in satoshis (prevents fee sniping)

## P2WPKH scriptCode

OP_DUP OP_HASH160 <20-byte-pubkey-hash> OP_EQUALVERIFY OP_CHECKSIG