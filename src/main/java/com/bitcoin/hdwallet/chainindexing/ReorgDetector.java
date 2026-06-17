/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.chaindata.StoredBlock;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcClient.ChainTip;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import java.sql.Connection;

import java.util.*;
import java.sql.*;

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

public class ReorgDetector {
    
    private static String className = "ReorgDetector";

    private static final int    MAX_REORG_DEPTH = 100;

    private final BitcoinRpcClient rpc;
    
    private final ChainIndexRepository chainStore;
    private final ChainDatabase sqLteDb;

    public ReorgDetector(BitcoinRpcClient rpc, ChainDatabase sqLteDb, 
            ChainIndexRepository chainStore) {
        this.rpc = rpc;
        this.sqLteDb = sqLteDb;
        this.chainStore  = chainStore;
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
            ourTip  = chainStore.getStoredTip(conn);

            if (ourTip == null) {
                AppLogger.info(className, "No local tip yet — skipping reorg check");
                return ReorgResult.noReorg(coreTip.height());
            }

            AppLogger.info(className, "ourTip={} coreTip={}",
                ourTip.label(), coreTip.hash());   //shortHash());

            if (ourTip.hashEquals(coreTip.hash())) {
                AppLogger.info(className, "Tips agree — no reorg");
                return ReorgResult.noReorg(coreTip.height());
            }

            AppLogger.info(className, "Tip mismatch! our={} core={}",
                ourTip.label(), coreTip.hash());   //shortHash());

            forkHeight = findForkPoint(conn, ourTip.height());
            if (forkHeight < 0) {
                String msg = "Fork point not found within "
                           + MAX_REORG_DEPTH + " blocks";
                AppLogger.error(className, "{}", msg);
                return ReorgResult.failed(msg);
            }

            rollbackDepth = ourTip.height() - forkHeight;
            AppLogger.info(className, "Fork at height={} — rolling back {} block(s)",
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
     *   ORDER  BY heig
     * @param conn
     * @param connht DESC
     *   LIMIT  1
     * @return 
     * @throws java.sql.SQLException
     */
    
    /*
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
                AppLogger.warn(className, "blocks table is empty — no stored tip");
                return null;
            }
            return rowToStoredBlock(row);
        //} //finally {
        //    sqLteDb.close(conn);
        //}
    }
    */

    // ─────────────────────────────────────────────────────────────────
    // findForkPoint
    // ─────────────────────────────────────────────────────────────────

    private int findForkPoint(Connection conn, int startHeight) throws Exception {
        int scanHeight = Math.min(startHeight, rpc.getBlockCount());
        int limit      = Math.max(0, scanHeight - MAX_REORG_DEPTH);

        while (scanHeight >= limit) {
            String coreHash = rpc.getBlockHash(scanHeight);
            String ourHash  = chainStore.getBlockHash(conn, scanHeight);

            AppLogger.info(className, "Scanning height={} core={} ours={}",
                scanHeight,
                coreHash.substring(0, 12),
                ourHash != null ? ourHash.substring(0, 12) : "null");

            if (ourHash != null && ourHash.equalsIgnoreCase(coreHash)) {
                AppLogger.warn(className, "Fork point found at height={}", scanHeight);
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
            // 1. collect orphaned txids
            List<String> orphanedTxids = chainStore.getTxidsAboveHeight(conn, forkHeight);
            AppLogger.info(className, "Orphaned txids: {}", orphanedTxids.size());

            // 2. restore spent UTXOs → unspent
            chainStore.unspendUtxosSpentByTxids(conn, orphanedTxids);

            // 3. delete UTXOs born in orphaned blocks
            chainStore.deleteUtxosCreatedAboveHeight(conn, forkHeight);

            // 4. delete orphaned transactions explicitly
            //    (also done by CASCADE but explicit is clearer)
            chainStore.deleteTransactionsAboveHeight(conn, forkHeight);

            // 5. delete orphaned blocks — CASCADE removes inputs + outputs
            chainStore.deleteBlocksAboveHeight(conn, forkHeight);

            // 6. update chain_tip
            StoredBlock newTip = chainStore.getBlockAtHeight(conn, forkHeight);
            if (newTip != null) {
                chainStore.updateChainTip(conn, newTip.hash(), forkHeight);
                AppLogger.info(className, "chain_tip → height={} hash={}",
                    forkHeight, newTip.hash().substring(0, 12));
            } else {
                AppLogger.info(className, "No block at forkHeight={} " +
                         "— chain_tip NOT updated", forkHeight);
            }

            //conn.commit();
            AppLogger.info(className, "Rollback complete: removed {} orphaned txids",
                orphanedTxids.size());
    }
    
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