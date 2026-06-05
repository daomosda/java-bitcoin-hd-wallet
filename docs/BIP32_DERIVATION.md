# BIP32 HD Key Derivation

## Path Structure

m / purpose' / coin_type' / account' / change / index
m / 84'      / 1'         / 0'       / 0      / 0

- `84'`  — BIP84 (native SegWit)
- `1'`   — testnet/regtest (0 for mainnet)
- `0'`   — first account
- `0`    — receive addresses
- `1`    — change addresses

## Derivation

```java
// Master key from seed
byte[] hmac = HMAC_SHA512("Bitcoin seed", seed);
byte[] masterKey  = hmac[0..31];
byte[] masterCode = hmac[32..63];

// Child key derivation (hardened)
byte[] data = 0x00 + masterKey + ser32(index | 0x80000000);
byte[] I    = HMAC_SHA512(masterCode, data);
byte[] childKey  = (parse256(I[0..31]) + masterKey) mod n;
byte[] childCode = I[32..63];
```


