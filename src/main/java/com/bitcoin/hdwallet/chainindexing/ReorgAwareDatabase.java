package com.bitcoin.hdwallet.chainindexing;

import com.bitcoin.hdwallet.chaindata.StoredBlock;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.database.ChainDatabase;
import java.sql.*;
import java.util.*;

public class ReorgAwareDatabase {

    private final ChainDatabase sqLteDb;
 
    public ReorgAwareDatabase(ChainDatabase sqLteDb) {
        this.sqLteDb = sqLteDb;
    }

    // ── rollbackToHeight — no lambda, plain JDBC transaction ─────────

    public void rollbackToHeight(int forkHeight) throws SQLException {
        Connection conn = sqLteDb.getConnection();
        try {
            sqLteDb.beginTransaction(conn);

            List<String> orphanTxids = getTxidsAboveHeight(conn, forkHeight);
            AppLogger.info("[ReorgDB] Orphaned txids above height {}: {}", forkHeight, orphanTxids.size());

            unspendUtxosSpentBy    (conn, orphanTxids);
            deleteUtxosCreatedAbove(conn, forkHeight);
            deleteBlocksAboveHeight(conn, forkHeight);  // CASCADE removes txs/inputs/outputs

            StoredBlock newTip = getBlockAtHeight(conn, forkHeight);
            if (newTip != null) {
                setChainTip(conn, newTip.hash(), forkHeight);
                AppLogger.info("[ReorgDB] New tip set: height={} hash={}",
                    forkHeight, newTip.hash());
            }

            sqLteDb.commit(conn);   //commit();
            AppLogger.info("[ReorgDB] Rolled back to height={} ({} orphan txids removed)",
                forkHeight, orphanTxids.size());

        } catch (SQLException e) {
            sqLteDb.rollback(conn);    //rollback();
            AppLogger.error("[ReorgDB] Rollback to height {} FAILED: {}", forkHeight, e.getMessage());
            throw e;
        } finally {
            sqLteDb.releaseThreadConnection();
        }
    }

    // ── getTxidsAboveHeight ───────────────────────────────────────────

    /**
     * Returns all txids from blocks above forkHeight.
     * These are the orphaned transactions that must be removed.
     *
     * SQL:
     *   SELECT txid FROM transactions WHERE block_height > forkHeight
     * @param conn
     * @param forkHeight
     * @return 
     * @throws java.sql.SQLException 
     */
    public List<String> getTxidsAboveHeight(Connection conn, int forkHeight)
            throws SQLException {
        return sqLteDb.queryStringList(conn,
            "SELECT txid FROM transactions WHERE block_height > ?",
            forkHeight);
    }

    // ── unspendUtxosSpentBy ───────────────────────────────────────────

    /**
     * Re-marks as unspent any UTXOs that were consumed by orphaned txids.
     * These UTXOs existed before the fork — they come back as unspent
     * once the orphaned blocks are discarded.
     *
     * SQL:
     *   UPDATE utxos
     *   SET    spent=0, spent_by_txid=NULL, spent_at_height=NULL
     *   WHERE  spent_by_txid IN (orphanedTxid1, orphanedTxid2, ...)
     * @param conn
     * @param orphanedTxids
     * @throws java.sql.SQLException
     */
    public void unspendUtxosSpentBy(Connection conn, List<String> orphanedTxids)
            throws SQLException {
        if (orphanedTxids.isEmpty()) {
            AppLogger.warn("[ReorgDB] No orphaned txids — nothing to unspend");
            return;
        }

        // build  IN (?,?,?)  dynamically
        String placeholders = buildPlaceholders(orphanedTxids.size());
        String sql = "UPDATE utxos " +
                     "SET    spent=0, spent_by_txid=NULL, spent_at_height=NULL " +
                     "WHERE  spent_by_txid IN (" + placeholders + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < orphanedTxids.size(); i++) {
                ps.setString(i + 1, orphanedTxids.get(i));
            }
            int rows = ps.executeUpdate();
            AppLogger.info("[ReorgDB] Unspent {} UTXO(s) consumed by orphaned txs", rows);
        }
    }

    // ── deleteUtxosCreatedAbove ───────────────────────────────────────

    /**
     * Deletes UTXOs that were first created in orphaned blocks.
     * These never existed in the canonical chain, so they must be removed.
     *
     * SQL:
     *   DELETE FROM utxos WHERE created_height > forkHeight
     * @param conn
     * @param forkHeight
     * @throws java.sql.SQLException
     */
    public void deleteUtxosCreatedAbove(Connection conn, int forkHeight)
            throws SQLException {
        int rows = sqLteDb.executeUpdate(conn,
            "DELETE FROM utxos WHERE created_height > ?",
            forkHeight);
        AppLogger.info("[ReorgDB] Deleted {} UTXO(s) created above height {}", rows, forkHeight);
    }

    // ── deleteBlocksAboveHeight ───────────────────────────────────────

    /**
     * Deletes all block rows above forkHeight.
     *
     * Because the schema has:
     *   transactions  → REFERENCES blocks(height)   ON DELETE CASCADE
     *   tx_inputs     → REFERENCES transactions(txid) ON DELETE CASCADE
     *   tx_outputs    → REFERENCES transactions(txid) ON DELETE CASCADE
     *
     * One DELETE on blocks cascades through all three child tables automatically.
     *
     * SQL:
     *   DELETE FROM blocks WHERE height > forkHeight
     * @param conn
     * @param forkHeight
     * @throws java.sql.SQLException
     */
    public void deleteBlocksAboveHeight(Connection conn, int forkHeight)
            throws SQLException {
        int rows = sqLteDb.executeUpdate(conn,
            "DELETE FROM blocks WHERE height > ?",
            forkHeight);
        AppLogger.info("[ReorgDB] Deleted {} block(s) above height {} (cascade applied)",
            rows, forkHeight);
    }

    // ── getBlockAtHeight ─────────────────────────────────────────────

    /**
     * Returns the StoredBlock at exactly forkHeight, or null if not found.
     * Used after rollback to find what the new tip should point to.
     *
     * SQL:
     *   SELECT * FROM blocks WHERE height = forkHeight
     * @param conn
     * @param height
     * @return 
     * @throws java.sql.SQLException 
     */
    public StoredBlock getBlockAtHeight(Connection conn, int height)
            throws SQLException {
        String sql =
            "SELECT height, hash, prev_hash, merkle_root, timestamp, " +
            "       bits, nonce, version, tx_count, size_bytes, weight, synced_at " +
            "FROM   blocks " +
            "WHERE  height = ?";

        Map<String, Object> row = sqLteDb.queryOne(conn, sql, height);
        if (row == null) {
            AppLogger.info("[ReorgDB] No block found at height={}", height);
            return null;
        }
        return rowToStoredBlock(row);
    }

    /**
     * Overload that opens its own connection — used by callers
     * outside a transaction (e.g. ReorgDetector.findForkPoint).
     * @param height
     * @return 
     * @throws java.sql.SQLException
     */
    //public StoredBlock getBlockAtHeight(Connection conn, int height) throws SQLException {
        //Connection conn = sqLteDb.getConnection();
        //try {
    //    return getBlockAtHeight(conn, height);
        //} finally {
        //    sqLteDb.close(conn);
        //}
    //}

    // ── setChainTip ───────────────────────────────────────────────────

    /**
     * Upserts the single-row chain_tip table.
     * There is always exactly one row (id=1).
     *
     * SQL:
     *   INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?)
     *   ON CONFLICT(id) DO UPDATE SET hash=?, height=?
     * @param conn
     * @param hash
     * @param height
     * @throws java.sql.SQLException
     */
    public void setChainTip(Connection conn, String hash, int height)
            throws SQLException {
        String sql =
            "INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?) " +
            "ON CONFLICT(id) DO UPDATE " +
            "SET hash=excluded.hash, height=excluded.height";
        sqLteDb.execute(conn, sql, hash, height);
        AppLogger.info("[ReorgDB] chain_tip → height={} hash={}", height, hash);
    }

    // ── ReorgDetector.rollback — no lambda ────────────────────────────

    /**
     * Called by ReorgDetector.  Identical logic to rollbackToHeight
     * but uses the method names that ReorgDetector expects.
     * Both methods share the same private helpers above.
     * @param forkHeight
     * @throws java.sql.SQLException
     */
    public void rollback(int forkHeight) throws SQLException {
        Connection conn = sqLteDb.getConnection();
        try {
            conn.setAutoCommit(false);

            List<String> orphanedTxids = getTxidsAboveHeight(conn, forkHeight);

            unspendUtxosSpentBy    (conn, orphanedTxids);
            deleteUtxosCreatedAbove(conn, forkHeight);
            deleteBlocksAboveHeight(conn, forkHeight);

            StoredBlock newTip = getBlockAtHeight(conn, forkHeight);
            if (newTip != null) {
                setChainTip(conn, newTip.hash(), forkHeight);
            }

            conn.commit();
            AppLogger.info("[ReorgDB] Rollback complete: height={} ({} orphaned txids removed)",
                forkHeight, orphanedTxids.size());

        } catch (SQLException e) {
            conn.rollback();
            AppLogger.error("[ReorgDB] Rollback FAILED at height {}: {}", forkHeight, e.getMessage());
            throw e;
        } 
    }
       
    // ── StoredBlock row mapping ───────────────────────────────────────

    private static StoredBlock rowToStoredBlock(Map<String, Object> row) {
        return new StoredBlock(
            toInt (row.get("height")),
            str   (row.get("hash")),
            str   (row.get("prev_hash")),
            str   (row.get("merkle_root")),
            toLong(row.get("timestamp")),
            str   (row.get("bits")),
            toLong(row.get("nonce")),
            toInt (row.get("version")),
            toInt (row.get("tx_count")),
            toInt (row.get("size_bytes")),
            toInt (row.get("weight")),
            toLong(row.get("synced_at"))
        );
    }

    // ── Tiny type-coercion helpers ────────────────────────────────────

    private static int    toInt (Object o) { return o == null ? 0  : ((Number) o).intValue();  }
    private static long   toLong(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static String str   (Object o) { return o == null ? "" : o.toString();             }

    // ── IN-clause placeholder builder ────────────────────────────────

    private static String buildPlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();  // "?,?,?"
    }
}