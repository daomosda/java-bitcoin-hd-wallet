package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author DAOMOSDA
 */


import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.database.ChainDatabase;

import java.math.BigDecimal;
import java.sql.*;
import java.util.List;

/**
 * Maps one RpcBlock into the five DB tables using plain JDBC.
 * Every writeBlock() call is one atomic transaction.
 */
public class ChainDataWriter {

    private final ChainDatabase sqLteDb;
    
    public ChainDataWriter(ChainDatabase sqLteDb) {
        this.sqLteDb = sqLteDb;
    }
        
    public void writeBlock(Connection conn, BlockData block) throws SQLException {

        insertBlock(conn, block);

        for (int i = 0; i < block.transactions().size(); i++) {

            TxData tx = block.transactions().get(i);
            boolean coinbase = isCoinbase(tx);
    
            long fee = 0L;
            if (!coinbase) {
                fee = computeFee(conn, tx);  // now safe ✅
            }

            insertTransaction(conn, tx, block, i, coinbase, fee);

            insertInputs(conn, tx, block.height(), coinbase);   // marks spent
            insertOutputs(conn, tx, block.height(), coinbase);  // creates UTXOs
        }

        upsertChainTip(conn, block.hash(), block.height());
    }
        
    private void markSpent(Connection conn, TxData tx) throws SQLException {

        String sql = """
            UPDATE transaction_outputs
            SET spent = 1
            WHERE txid = ? AND vout = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            for (int i = 0; i < tx.inputs().size(); i++) {

                TxInputData in = tx.inputs().get(i);

                // Skip coinbase input
                if (in.coinbaseData() != null) {
                    continue;
                }

                ps.setString(1, in.prevTxId());
                ps.setInt(2, in.prevVout());

                int rows = ps.executeUpdate();

                if (rows == 0) {
                    throw new SQLException(
                        "markSpent: output not found: "
                        + in.prevTxId() + ":" + in.prevVout()
                    );
                }
            }
        }
    }

    // ── 1. blocks ─────────────────────────────────────────────────────

    private void insertBlock(Connection conn, BlockData block) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO blocks " +
                        "  (height, hash, prev_hash, merkle_root, timestamp, " +
                        "   bits, nonce, version, tx_count, size_bytes, weight) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt   (1,  block.height());
            ps.setString(2,  block.hash());
            ps.setString(3,  block.prevHash() != null
                                ? block.prevHash() : "");
            ps.setString(4,  block.merkleRoot());
            ps.setLong  (5,  block.timestamp());
            ps.setString(6,  block.bits());
            ps.setLong  (7,  block.nonce());
            ps.setInt   (8,  block.version());
            ps.setInt   (9,  block.transactions().size());
            ps.setInt   (10, block.size());
            ps.setInt   (11, block.weight());
            ps.executeUpdate();
        }
    }

    // ── 2. transactions ───────────────────────────────────────────────    
    private void insertTransaction(Connection conn,
                                    TxData     tx,
                                    BlockData   block,
                                    int        txIdx,
                                    boolean    isCoinbase,
                                    long       feeSat) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO transactions " +
                        "  (txid, block_height, block_hash, tx_index, version, locktime, " +
                        "   is_coinbase, input_count, output_count, fee_sat, " +
                        "   size_bytes, vsize_bytes, weight, raw_hex) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString (1,  tx.txid());
            ps.setInt    (2,  block.height());
            ps.setString (3,  block.hash());
            ps.setInt    (4,  txIdx);
            ps.setInt    (5,  tx.version());
            ps.setLong   (6,  tx.locktime());
            ps.setInt    (7,  isCoinbase ? 1 : 0);
            ps.setInt    (8,  tx.inputs().size());
            ps.setInt    (9,  tx.outputs().size());
            ps.setLong   (10, feeSat);
            ps.setInt    (11, tx.size());
            ps.setInt    (12, tx.vsize());
            ps.setInt    (13, tx.weight());
            ps.setString (14, tx.rawHex());
            ps.executeUpdate();
        }
    }
    
    // ── 3. transaction_inputs ─────────────────────────────────────────

    private void insertInputs(Connection conn,
                               TxData      tx,
                               int        blockHeight,
                               boolean    isCoinbase) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO transaction_inputs " +
                        "  (txid, input_index, prev_txid, prev_vout, " +
                        "   script_sig_hex, sequence, witness_hex) " +
                        "VALUES (?,?,?,?,?,?,?)")) {
            for (int i = 0; i < tx.inputs().size(); i++) {
                TxInputData vin = tx.inputs().get(i);

                ps.setString(1, tx.txid());
                ps.setInt   (2, i);

                if (vin.isCoinbase()) {
                    ps.setNull  (3, Types.VARCHAR);
                    ps.setNull  (4, Types.INTEGER);
                    ps.setString(5, vin.coinbaseData());
                } else {
                    ps.setString(3, vin.prevTxId());
                    ps.setInt   (4, vin.prevVout());
                    ps.setString(5, vin.scriptSigHex());
                }

                ps.setLong  (6, vin.sequence());
                ps.setString(7, witnessToJson(vin.witness()));
                ps.addBatch();

                // mark the referenced UTXO spent
                if (!vin.isCoinbase() && vin.prevTxId() != null) {
                    markUtxoSpent(conn,
                        vin.prevTxId(), vin.prevVout(), tx.txid(), blockHeight);
                }
            }
            ps.executeBatch();
        }
    }

    // ── 4. transaction_outputs + 5. utxos (create) ───────────────────

    private void insertOutputs(Connection conn,
                                TxData      tx,
                                int        blockHeight,
                                boolean    isCoinbase) throws SQLException {
        PreparedStatement outPs = conn.prepareStatement(
            "INSERT OR IGNORE INTO transaction_outputs " +
            "  (txid, vout, value_sat, script_hex, script_type, address) " +
            "VALUES (?,?,?,?,?,?)");
       
        String upsertSql = """
            INSERT INTO utxos (txid, vout, address, script_pubkey, amount_sat,
                               confirmations, spendable, solvable, safe, descriptor)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (txid, vout) DO UPDATE SET
                address = excluded.address,
                script_pubkey = excluded.script_pubkey,
                amount_sat = excluded.amount_sat,
                confirmations = excluded.confirmations,
                spendable = excluded.spendable,
                solvable = excluded.solvable,
                safe = excluded.safe,
                descriptor = excluded.descriptor
        """;
        PreparedStatement utxoPs = conn.prepareStatement(upsertSql);
                
        try {
            for (TxOutputData vout : tx.outputs()) {
                long    valueSat = BlockDataMapper.toSats(BigDecimal.valueOf(vout.valueSat()));
                boolean opReturn = "nulldata".equals(vout.scriptType());

                // every output → transaction_outputs
                outPs.setString(1, tx.txid());
                outPs.setInt   (2, vout.vout());
                outPs.setLong  (3, valueSat);
                outPs.setString(4, vout.scriptHex());
                outPs.setString(5, vout.scriptType());
                outPs.setString(6, vout.address());
                outPs.addBatch();

                // spendable outputs only → utxos
                if (!opReturn) {                    
                    int chainTip = getChainTipHeight(conn);
                    int confirmations = (chainTip >= blockHeight)
                            ? (chainTip - blockHeight + 1)
                            : 0;

                    String descriptor = deriveDescriptor(vout);
                    boolean solvable  = deriveSolvable(vout);   // ✅ NEW
                    
                    utxoPs.setString(1, tx.txid());
                    utxoPs.setInt(2, vout.vout());
                    utxoPs.setString(3, vout.address());
                    utxoPs.setString(4, vout.scriptHex());

                    utxoPs.setLong(5, valueSat);

                    utxoPs.setInt(6, confirmations);
                    utxoPs.setBoolean(7, vout.isSpendable());
                    utxoPs.setBoolean(8, solvable);
                    utxoPs.setBoolean(9, true);
                    utxoPs.setString(10, descriptor);
                    
                    utxoPs.addBatch();
                }
            }
            outPs.executeBatch();
            utxoPs.executeBatch();
        } finally {
            outPs.close();
            utxoPs.close();
        }
    }

    // ── 5b. utxos (mark spent) ────────────────────────────────────────

    private void markUtxoSpent(Connection conn,
                                String     prevTxid,
                                int        prevVout,
                                String     spenderTxid,
                                int        height) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE utxos " +
                        "SET    spent=1, spent_by_txid=?, spent_at_height=? " +
                        "WHERE  txid=? AND vout=? AND spent=0")) {
            ps.setString(1, spenderTxid);
            ps.setInt   (2, height);
            ps.setString(3, prevTxid);
            ps.setInt   (4, prevVout);
            ps.executeUpdate();
        }
    }
    
    public int getChainTipHeight(Connection conn) throws SQLException {
        String sql = "SELECT COALESCE(MAX(height), -1) AS tip FROM blocks";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getInt("tip");
            }
            return -1;
        }
    }

    // ── chain_tip ─────────────────────────────────────────────────────

    private void upsertChainTip(Connection conn,
                                 String hash, int height) throws SQLException {
        String sql = """
            INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE
               SET hash   = excluded.hash,
                   height = excluded.height
            """;
        sqLteDb.execute(conn, sql, hash, height);
    }

    // ── Fee calculation ───────────────────────────────────────────────

    private long computeFee(Connection conn, TxData tx) throws SQLException {
        long inputTotal  = 0L;
        for (TxInputData vin : tx.inputs()) {
            if (vin.isCoinbase()) return 0L;

            // look up what the previous output was worth
            long prevVal = sqLteDb.queryLong(conn,
                "SELECT value_sat FROM transaction_outputs WHERE txid=? AND vout=?",
                vin.prevTxId(), vin.prevVout());

            if (prevVal == 0 && !outputExists(conn, vin.prevTxId(), vin.prevVout())) {
                AppLogger.warn("[Writer] Prev output {}:{} not found — fee=0",
                    vin.prevTxId(), vin.prevVout());
                return 0L;
            }
            inputTotal += prevVal;
        }
        long outputTotal = tx.getOutputs().stream()
            .mapToLong(o -> toSats(BigDecimal.valueOf(o.valueSat())))
            .sum();
        return Math.max(0L, inputTotal - outputTotal);
    }

    private boolean outputExists(Connection conn,
                                  String txid, int vout) throws SQLException {
        long count = sqLteDb.queryLong(conn,
            "SELECT COUNT(*) FROM transaction_outputs WHERE txid=? AND vout=?",
            txid, vout);
        return count > 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean isCoinbase(TxData tx) {
        return !tx.inputs().isEmpty() && tx.inputs().get(0).isCoinbase();
    }

    private static long toSats(BigDecimal btc) {
        if (btc == null) return 0L;
        return btc.multiply(BigDecimal.valueOf(100_000_000L)).longValue();
    }

    private static String witnessToJson(List<String> witness) {
        if (witness == null || witness.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < witness.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(witness.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
    
    private boolean deriveSolvable(TxOutputData out) {

        String type = out.scriptType();

        return switch (type.toLowerCase()) {
            case "p2pkh",
                 "pubkeyhash",
                 "p2wpkh",
                 "witness_v0_keyhash",
                 "p2sh",
                 "p2tr",
                 "witness_v1_taproot" -> true;

            default -> false; // unknown or non-standard
        };
    }
    
    private String deriveDescriptor(TxOutputData out) {

        String addr = out.address();
        String type = out.scriptType();

        if (addr == null || addr.isEmpty()) {
            return ""; // non-standard
        }

        return switch (type.toLowerCase()) {

            case "witness_v0_keyhash", "p2wpkh" ->
                "wpkh(" + addr + ")";

            case "witness_v1_taproot", "p2tr" ->
                "tr(" + addr + ")";

            case "pubkeyhash", "p2pkh" ->
                "pkh(" + addr + ")";

            case "scripthash", "p2sh" ->
                "sh(" + addr + ")";

            default ->
                "addr(" + addr + ")"; // fallback
        };
    }
}