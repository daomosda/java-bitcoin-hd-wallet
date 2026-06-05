# Descriptor Wallet Integration

## Overview

Bitcoin Core descriptor wallets replace legacy importaddress/importpubkey.

## Descriptor Format Used

wpkh([fingerprint/84h/1h/0h]xpub/change/index)

## Import Flow

Derive account xpub at m/84'/1'/0'
Build wpkh([fp/84h/1h/0h]xpub/0/*) for receive
Build wpkh([fp/84h/1h/0h]xpub/1/*) for change
getdescriptorinfo → add checksum
importdescriptors with timestamp=0 and active=true
rescanblockchain(0)

## Key Parameters

- `timestamp=0` — scan from genesis block
- `active=true` — wallet manages keypool
- `internal=true` — change addresses
- `range=[0,999]` — pre-derive 1000 addresses
