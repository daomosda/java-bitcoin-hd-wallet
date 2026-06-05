# PSBT Signing Flow

## Overview

PSBT (BIP174) is the standard format for partially signed Bitcoin transactions.

## Flow

walletcreatefundedpsbt
Parse PSBT bytes (BIP174 binary format)
Extract bip32_derivs from each input
Derive private key: masterKey.derivePath(path)
Compute BIP143 sighash for each input
Sign: ECDSA(privKey, sighash) → DER signature + SIGHASH_ALL
Inject partial_sig into PSBT input
finalizepsbt → extract raw tx hex
sendrawtransaction → txid

## BIP143 Sighash Construction

```
// nVersion
preimage.write(intToLE(version));
// hashPrevouts
preimage.write(hashPrevouts);
// hashSequence
preimage.write(hashSequence);
// outpoint
preimage.write(outpoint.toByteArray());
// scriptCode
preimage.write(compactSize(scriptCode.length));
preimage.write(scriptCode);
// value
preimage.write(longToLE(valueSat));
// nSequence
preimage.write(intToLE((int) sequence));
// hashOutputs
preimage.write(hashOutputs);
// nLockTime
preimage.write(intToLE((int) locktime));
// sighash type (SIGHASH_ALL)
preimage.write(intToLE(1));

byte[] preimageBytes = preimage.toByteArray();
result = doubleSha256(preimageBytes);
```
