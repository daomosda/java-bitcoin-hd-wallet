package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.chaindata.StoredBlock;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcClient.ChainTip;
import com.bitcoin.hdwallet.database.ChainDatabase;
import java.sql.Connection;

import java.util.*;

/**
 * Detects and recovers from blockchain reorganizations.
 *
 * Strategy:
 *   1. On every sync tick, walk backward from Core's current tip comparing
 *      Core's block hashes against our stored hashes.
 *   2. The first height where they agree is the "fork point".
 *   3. Roll back everything above the fork point atomically.
 *   4. Re-sync forward from the fork point to Core's new tip.
 */
// ── ReorgDetector.java — complete, all methods implemented ───────────

import java.sql.*;

public class ReorgDetector {

    private static final int    MAX_REORG_DEPTH = 100;

    private final BitcoinRpcClient rpc;
    //private final CentralDBAccess         sqLteDb;
    //private final H2Database sqLteDb;
    private final ChainDatabase sqLteDb;

    public ReorgDetector(BitcoinRpcClient rpc, ChainDatabase sqLteDb) {
        this.rpc = rpc;
        //this.sqLteDb  = sqLteDb;
        this.sqLteDb  = sqLteDb;
    }

    // ─────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────

    public ReorgResult detectAndRecover() throws Exception {
        Connection conn = sqLteDb.getConnection();
        ChainTip    coreTip = (ChainTip) rpc.getChainTip();
        StoredBlock ourTip;
        int forkHeight;
        int rollbackDepth;
        try {
        sqLteDb.beginTransaction(conn);
        ourTip  = getStoredTip(conn);

        if (ourTip == null) {
            AppLogger.info("[ReorgDetector] No local tip yet — skipping reorg check");
            return ReorgResult.noReorg(coreTip.height());
        }

        AppLogger.info("[ReorgDetector] ourTip={} coreTip={}",
            ourTip.label(), coreTip.hash());   //shortHash());

        if (ourTip.hashEquals(coreTip.hash())) {
            AppLogger.info("[ReorgDetector] Tips agree — no reorg");
            return ReorgResult.noReorg(coreTip.height());
        }

        AppLogger.info("[ReorgDetector] Tip mismatch! our={} core={}",
            ourTip.label(), coreTip.hash());   //shortHash());

        forkHeight = findForkPoint(conn, ourTip.height());
        if (forkHeight < 0) {
            String msg = "Fork point not found within "
                       + MAX_REORG_DEPTH + " blocks";
            AppLogger.error("[ReorgDetector] {}", msg);
            return ReorgResult.failed(msg);
        }

        rollbackDepth = ourTip.height() - forkHeight;
        AppLogger.info("[ReorgDetector] Fork at height={} — rolling back {} block(s)",
            forkHeight, rollbackDepth);

        rollback(conn, forkHeight);
        sqLteDb.commit(conn);
        } catch (SQLException e) {
            sqLteDb.rollback(conn);
            throw e;
        } finally {
            sqLteDb.releaseThreadConnection();
        }
        return ReorgResult.reorg(forkHeight, rollbackDepth, coreTip.height());
    }

    // ─────────────────────────────────────────────────────────────────
    // getStoredTip — reads highest block from our DB
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns the highest block we have stored, or null if the DB is empty.
     *
     * SQL:
     *   SELECT height, hash, prev_hash, merkle_root, timestamp,
     *          bits, nonce, version, tx_count, size_bytes, weight, synced_at
     *   FROM   blocks
     *   ORDER  BY height DESC
     *   LIMIT  1
     * @return 
     * @throws java.sql.SQLException
     */
    public StoredBlock getStoredTip(Connection conn) throws SQLException {
       // Connection conn = sqLteDb.getConnection();
        //try {
            String sql =
                "SELECT height, hash, prev_hash, merkle_root, timestamp, " +
                "       bits, nonce, version, tx_count, size_bytes, " +
                "       weight, synced_at " +
                "FROM   blocks " +
                "ORDER  BY height DESC " +
                "LIMIT  1";

            Map<String, Object> row = sqLteDb.queryOne(conn, sql);
            if (row == null) {
                AppLogger.warn("[ReorgDetector] blocks table is empty — no stored tip");
                return null;
            }
            return rowToStoredBlock(row);
        //} //finally {
        //    sqLteDb.close(conn);
        //}
    }

    // ─────────────────────────────────────────────────────────────────
    // findForkPoint
    // ─────────────────────────────────────────────────────────────────

    private int findForkPoint(Connection conn, int startHeight) throws Exception {
        int scanHeight = Math.min(startHeight, rpc.getBlockCount());
        int limit      = Math.max(0, scanHeight - MAX_REORG_DEPTH);

        while (scanHeight >= limit) {
            String coreHash = rpc.getBlockHash(scanHeight);
            String ourHash  = getBlockHash(conn, scanHeight);

            AppLogger.info("[ReorgDetector] Scanning height={} core={} ours={}",
                scanHeight,
                coreHash.substring(0, 12),
                ourHash != null ? ourHash.substring(0, 12) : "null");

            if (ourHash != null && ourHash.equalsIgnoreCase(coreHash)) {
                AppLogger.warn("[ReorgDetector] Fork point found at height={}", scanHeight);
                return scanHeight;
            }
            scanHeight--;
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────
    // rollback — single atomic transaction, no lambdas
    // ─────────────────────────────────────────────────────────────────

    /**
     * Rolls back all DB state above forkHeight atomically:
     *
     *  1. Collect orphaned txids
     *  2. Restore UTXOs those txs spent  → unspent
     *  3. Delete UTXOs created in orphaned blocks
     *  4. Delete orphaned transactions explicitly
     *  5. Delete orphaned blocks  (CASCADE removes inputs + outputs)
     *  6. Update chain_tip to forkHeight
     */
    private void rollback(Connection conn, int forkHeight) throws SQLException {
        //Connection conn = sqLteDb.getConnection();
        //try {
            //conn.setAutoCommit(false);

            // 1. collect orphaned txids
            List<String> orphanedTxids = getTxidsAboveHeight(conn, forkHeight);
            AppLogger.info("[ReorgDetector] Orphaned txids: {}", orphanedTxids.size());

            // 2. restore spent UTXOs → unspent
            unspendUtxosSpentByTxids(conn, orphanedTxids);

            // 3. delete UTXOs born in orphaned blocks
            deleteUtxosCreatedAboveHeight(conn, forkHeight);

            // 4. delete orphaned transactions explicitly
            //    (also done by CASCADE but explicit is clearer)
            deleteTransactionsAboveHeight(conn, forkHeight);

            // 5. delete orphaned blocks — CASCADE removes inputs + outputs
            deleteBlocksAboveHeight(conn, forkHeight);

            // 6. update chain_tip
            StoredBlock newTip = getBlockAtHeight(conn, forkHeight);
            if (newTip != null) {
                updateChainTip(conn, newTip.hash(), forkHeight);
                AppLogger.info("[ReorgDetector] chain_tip → height={} hash={}",
                    forkHeight, newTip.hash().substring(0, 12));
            } else {
                AppLogger.info("[ReorgDetector] No block at forkHeight={} " +
                         "— chain_tip NOT updated", forkHeight);
            }

            //conn.commit();
            AppLogger.info("[ReorgDetector] Rollback complete: removed {} orphaned txids",
                orphanedTxids.size());

        //} catch (SQLException e) {
        //    conn.rollback();
        //    AppLogger.error("[ReorgDetector] Rollback FAILED: {}", e.getMessage());
        //    throw e;
        //} 
    }

    // ─────────────────────────────────────────────────────────────────
    // getTxidsAboveHeight
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns every txid whose block_height > forkHeight.
     * These are the orphaned transactions that must be unwound.
     *
     * SQL:
     *   SELECT txid FROM transactions WHERE block_height > ?
     */
    private List<String> getTxidsAboveHeight(Connection conn,
                                              int forkHeight)
            throws SQLException {
        List<String>      txids = new ArrayList<>();
        PreparedStatement ps    = conn.prepareStatement(
            "SELECT txid FROM transactions WHERE block_height > ?");
        try {
            ps.setInt(1, forkHeight);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) txids.add(rs.getString("txid"));
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        AppLogger.info("[ReorgDetector] getTxidsAboveHeight({}) → {} txids",
            forkHeight, txids.size());
        return txids;
    }

    // ─────────────────────────────────────────────────────────────────
    // unspendUtxosSpentByTxids
    // ─────────────────────────────────────────────────────────────────

    /**
     * Re-marks as unspent every UTXO that was consumed by an
     * orphaned transaction.  These UTXOs pre-date the fork and
     * must be returned to the spendable pool.
     *
     * SQL:
     *   UPDATE utxos
     *   SET    spent=0, spent_by_txid=NULL, spent_at_height=NULL
     *   WHERE  spent_by_txid IN (?,?,?)
     */
    private void unspendUtxosSpentByTxids(Connection conn,
                                           List<String> orphanedTxids)
            throws SQLException {
        if (orphanedTxids.isEmpty()) {
            AppLogger.info("[ReorgDetector] No orphaned txids — nothing to unspend");
            return;
        }

        String placeholders = buildPlaceholders(orphanedTxids.size());
        String sql =
            "UPDATE utxos " +
            "SET    spent=0, spent_by_txid=NULL, spent_at_height=NULL " +
            "WHERE  spent_by_txid IN (" + placeholders + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < orphanedTxids.size(); i++) {
                ps.setString(i + 1, orphanedTxids.get(i));
            }
            int rows = ps.executeUpdate();
            AppLogger.info("[ReorgDetector] unspendUtxosSpentByTxids: restored {} row(s)", rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // deleteUtxosCreatedAboveHeight
    // ─────────────────────────────────────────────────────────────────

    /**
     * Deletes UTXOs that were first seen in orphaned blocks.
     * They never existed in the canonical chain.
     *
     * SQL:
     *   DELETE FROM utxos WHERE created_height > ?
     */
    private void deleteUtxosCreatedAboveHeight(Connection conn,
                                                int forkHeight)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM utxos WHERE block_height > ?")) {
            ps.setInt(1, forkHeight);
            int rows = ps.executeUpdate();
            AppLogger.info("[ReorgDetector] deleteUtxosCreatedAboveHeight({}): " +
                      "deleted {} row(s)", forkHeight, rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // deleteTransactionsAboveHeight
    // ─────────────────────────────────────────────────────────────────

    /**
     * Deletes orphaned transactions.
     * ON DELETE CASCADE on transaction_inputs and transaction_outputs
     * removes their child rows automatically.
     *
     * SQL:
     *   DELETE FROM transactions WHERE block_height > ?
     */
    private void deleteTransactionsAboveHeight(Connection conn,
                                                int forkHeight)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM transactions WHERE block_height > ?")) {
            ps.setInt(1, forkHeight);
            int rows = ps.executeUpdate();
            AppLogger.info("[ReorgDetector] deleteTransactionsAboveHeight({}): " +
                      "deleted {} row(s)", forkHeight, rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // deleteBlocksAboveHeight
    // ─────────────────────────────────────────────────────────────────

    /**
     * Deletes orphaned block header rows.
     * ON DELETE CASCADE propagates to transactions → inputs + outputs.
     *
     * SQL:
     *   DELETE FROM blocks WHERE height > ?
     */
    private void deleteBlocksAboveHeight(Connection conn, int forkHeight)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM blocks WHERE height > ?")) {
            ps.setInt(1, forkHeight);
            int rows = ps.executeUpdate();
            AppLogger.info("[ReorgDetector] deleteBlocksAboveHeight({}): " +
                      "deleted {} block(s) (cascade applied)", forkHeight, rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // updateChainTip
    // ─────────────────────────────────────────────────────────────────

    /**
     * Upserts the single-row chain_tip table so it points to the
     * new canonical tip after rollback.
     *
     * SQL:
     *   INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?)
     *   ON CONFLICT(id) DO UPDATE
     *      SET hash=excluded.hash, height=excluded.height
     */
    private void updateChainTip(Connection conn, String hash, int height)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE " +
                        "SET hash=excluded.hash, height=excluded.height")) {
            ps.setString(1, hash);
            ps.setInt   (2, height);
            ps.executeUpdate();
            AppLogger.info("[ReorgDetector] updateChainTip → height={} hash={}",
                height, hash.substring(0, 12));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // getBlockHash  — looks up our stored hash at a given height
    // ─────────────────────────────────────────────────────────────────

    /**
     * SQL:
     *   SELECT hash FROM blocks WHERE height = ?
     */
    private String getBlockHash(Connection conn, int height) throws SQLException {
        //Connection conn = sqLteDb.getConnection();
        //try {
        return sqLteDb.queryString(conn,
            "SELECT hash FROM blocks WHERE height = ?", height);
        //} //finally {
        //    close(conn);
        //}
    }

    /*
    public void beginTransaction(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
    }

    public void commit(Connection conn) throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);
    }

    public void rollback(Connection conn) {
        try {
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            AppLogger.error("[Database] Rollback failed: {}", e.getMessage());
        }
    }

    public void close(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            AppLogger.warn("[Database] Close failed: {}", e.getMessage());
        }
    }
    */
    
    // ─────────────────────────────────────────────────────────────────
    // getBlockAtHeight — used to find the new tip after rollback
    // ─────────────────────────────────────────────────────────────────

    /**
     * SQL:
     *   SELECT * FROM blocks WHERE height = ?
     */
    private StoredBlock getBlockAtHeight(Connection conn, int height)
            throws SQLException {
        String sql =
            "SELECT height, hash, prev_hash, merkle_root, timestamp, " +
            "       bits, nonce, version, tx_count, size_bytes, " +
            "       weight, synced_at " +
            "FROM   blocks " +
            "WHERE  height = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, height);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    AppLogger.info("[ReorgDetector] No block at height={}", height);
                    return null;
                }
                return new StoredBlock(
                    rs.getInt   ("height"),
                    rs.getString("hash"),
                    rs.getString("prev_hash"),
                    rs.getString("merkle_root"),
                    rs.getLong  ("timestamp"),
                    rs.getString("bits"),
                    rs.getLong  ("nonce"),
                    rs.getInt   ("version"),
                    rs.getInt   ("tx_count"),
                    rs.getInt   ("size_bytes"),
                    rs.getInt   ("weight"),
                    rs.getLong  ("synced_at")
                );
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────

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

    /** Builds "?,?,?" for n parameters. */
    private static String buildPlaceholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static int    toInt (Object o) { return o == null ? 0  : ((Number)o).intValue();  }
    private static long   toLong(Object o) { return o == null ? 0L : ((Number)o).longValue(); }
    private static String str   (Object o) { return o == null ? "" : o.toString();            }

    // ─────────────────────────────────────────────────────────────────
    // ReorgResult
    // ─────────────────────────────────────────────────────────────────

    public static final class ReorgResult {

        private final boolean reorgOccurred;
        private final boolean success;
        private final int     forkHeight;
        private final int     rollbackDepth;
        private final long    currentHeight;
        private final String  errorMessage;

        private ReorgResult(boolean reorgOccurred,
                            boolean success,
                            int     forkHeight,
                            int     rollbackDepth,
                            long    currentHeight,
                            String  errorMessage) {
            this.reorgOccurred  = reorgOccurred;
            this.success        = success;
            this.forkHeight     = forkHeight;
            this.rollbackDepth  = rollbackDepth;
            this.currentHeight  = currentHeight;
            this.errorMessage   = errorMessage;
        }

        public static ReorgResult noReorg(long tip) {
            return new ReorgResult(false, true, -1, 0, tip, null);
        }

        public static ReorgResult reorg(int fork, int depth, long tip) {
            return new ReorgResult(true, true, fork, depth, tip, null);
        }

        public static ReorgResult failed(String reason) {
            return new ReorgResult(true, false, -1, 0, -1, reason);
        }

        public boolean reorgOccurred()  { return reorgOccurred;  }
        public boolean success()        { return success;         }
        public int     forkHeight()     { return forkHeight;      }
        public int     rollbackDepth()  { return rollbackDepth;   }
        public long    currentHeight()  { return currentHeight;   }
        public String  errorMessage()   { return errorMessage;    }

        @Override
        public String toString() {
            if (!reorgOccurred) return "ReorgResult{none tip=" + currentHeight + "}";
            if (!success)       return "ReorgResult{FAILED: "  + errorMessage  + "}";
            return "ReorgResult{fork=" + forkHeight
                 + " depth="  + rollbackDepth
                 + " tip="    + currentHeight + "}";
        }
    }
}