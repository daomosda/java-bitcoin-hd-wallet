# Lightning Payment Channel Tooling
## Components
### HTLC State Machine
- ADD_HTLC — propose a payment
- FULFILL_HTLC — reveal preimage
- FAIL_HTLC — reject payment
- SETTLE — remove from channel state
### Invoice System
- Generate payment hash (SHA256 of preimage)
- Encode as Lightning invoice
- Verify preimage on receipt
### Onion Routing (BOLT 4)
- Sphinx packet construction
- Per-hop payload encryption
- ECDH shared secret via secp256k1
## Flow
Customer Merchant
│ │
│ 1. Request invoice │
│◄────────────────────────── │
│ 2. pay(invoice) │
│──────────────────────────► │
│ 3. HTLC add │
│ 4. preimage revealed │
│◄────────────────────────── │
│ 5. HTLC settled
