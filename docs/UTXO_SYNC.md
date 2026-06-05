# UTXO Synchronization

## Database Schema

```sql
blocks       (height, hash, prev_hash, merkle_root, timestamp, bits, nonce, version, tx_count, size_bytes, weight)
transactions (txid, block_height, block_hash, tx_index, version, locktime, is_coinbase, input_count, output_count, fee_sat, 
                       size_bytes, vsize_bytes, weight, raw_hex)
transaction_inputs    (txid, input_index, prev_txid, prev_vout, script_sig_hex, sequence, witness_hex)
transaction_outputs   (txid, vout, value_sat, script_hex, script_type, address)
utxos        (txid, vout, address, script_pubkey, amount_sat, confirmations, spendable, solvable, safe, descriptor)
```

## Sync Flow

getLastSyncedHeight from DB
getblockcount from Core
For each new block:
a. getblockhash(height)
b. getblock(hash, verbosity=2)
c. Mark spent inputs
d. Create new UTXOs
Update chain_tip

## Reorg Detection

Compare local tip hash vs Core tip hash
If mismatch: walk back until common ancestor
Roll back: unspend inputs, delete UTXOs, delete blocks
Re-sync from fork point
