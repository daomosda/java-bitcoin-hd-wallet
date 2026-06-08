package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.inputcontrol.SharedMonitor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import java.sql.*;

/**
 * Continuously syncs Bitcoin Core's chain into our local DB.
 *
 * Responsibilities:
 *   • Initial sync from genesis (or from wherever we left off)
 *   • Incremental sync on new blocks
 *   • Reorg detection and rollback
 *   • Populating all five tables atomically per block
 */
public class ChainIndexer implements Runnable {

    private static final int BATCH_LOG_INTERVAL = 500; // log every N blocks during IBD

    private final BitcoinRpcClient      rpc;
    private final ChainDatabase sqLteDb;
    
    private final ChainIndexRepository       store;
    private final ReorgDetector         reorgDetector;
    //private final ScheduledExecutorService scheduler;
    
    private final SharedMonitor monitor;
    private final int id = 2;

    // Track total progress for IBD logging
    private final AtomicLong blocksApplied = new AtomicLong(0);

    public ChainIndexer(BitcoinRpcClient rpc, ChainDatabase sqLteDb, 
            ChainIndexRepository store, SharedMonitor monitor) {
        this.rpc           = rpc;
        this.sqLteDb         = sqLteDb;
        this.store         = store;
        this.monitor = monitor;
        this.reorgDetector = new ReorgDetector(this.rpc, this.sqLteDb);
        //this.scheduler     = Executors.newSingleThreadScheduledExecutor(r -> {
        //    Thread t = new Thread(r, "chain-indexer");
        //    t.setDaemon(true);
        //    return t;
        //});
    }

    // ── Lifecycle ────────────────────────────────────────────────────
    @Override
    public void run() {
        try {
            //catchUp();
            while (true) {
                try {
                    // 1. Wait for signal from Controller
                    monitor.waitForSignal(id);
                    
                    // 2. Perform the task
                    AppLogger.info("ChainIndexer: Executing task...");
                    incrementalSync();
                    //Thread.sleep(1500); // Simulate work
                    AppLogger.info("ChainIndexer: Task finished.");
                    
                    // 3. Update shared variables to indicate free
                    monitor.markWorkerFree(id);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    System.getLogger(ChainIndexer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                }
            }
        } catch (Exception ex) {
            System.getLogger(ChainIndexer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
    /**
     * Syncs from our stored tip (or block 0) up to Core's current tip.
     * @throws java.lang.Exception
     */
    public void initialSync() throws Exception {
        Connection conn = sqLteDb.getConnection();   // thread's own connection
        try {
            sqLteDb.beginTransaction(conn); 
            int localHeight  = store.getLocalTipHeight(conn);     // -1 if empty

            int coreHeight   = rpc.getBlockCount();

            AppLogger.info("[ChainIndexer] Local tip: {}  Core tip: {}", localHeight, coreHeight);

            if (localHeight >= coreHeight) {
                AppLogger.info("[ChainIndexer] Already at tip — nothing to sync");
                return;
            }

            AppLogger.info("[ChainIndexer] Syncing {} blocks ({} → {})",
                coreHeight - localHeight, localHeight + 1, coreHeight);

            applyRange(conn, localHeight + 1, coreHeight);
            sqLteDb.commit(conn);
        } catch (SQLException e) {
            if (!conn.getAutoCommit()) { // ✅ SAFE GUARD
                sqLteDb.rollback(conn);   //conn.rollback();
            }
            AppLogger.error("[Sync] Error: {}", e.getMessage());
            throw e;        
        } finally {
            sqLteDb.releaseThreadConnection();
        } 
    }

    /**
     * Called on every poll tick: checks for reorg then applies new blocks.
     */
    private void incrementalSync() throws Exception {
        // 1. Reorg check first
        ReorgDetector.ReorgResult reorg = reorgDetector.detectAndRecover();
        if (reorg.reorgOccurred() && !reorg.success()) {
            AppLogger.error("[ChainIndexer] Reorg recovery failed — pausing sync");
            return;
        }

        // 2. Advance to new tip
        Connection conn = sqLteDb.getConnection();   // thread's own connection
        //int localHeight;
        try {
            sqLteDb.beginTransaction(conn); 
            int localHeight = store.getLocalTipHeight(conn);

            int coreHeight  = rpc.getBlockCount();

            if (localHeight < coreHeight) {
                AppLogger.info("[ChainIndexer] Applying {} new block(s) ({} → {})",
                    coreHeight - localHeight, localHeight + 1, coreHeight);
                applyRange(conn, localHeight + 1, coreHeight);
            }
            sqLteDb.commit(conn);
        } catch (SQLException e) {
            if (!conn.getAutoCommit()) { // ✅ SAFE GUARD
                sqLteDb.rollback(conn);   //conn.rollback();
            }
            throw e;        
        } finally {
            sqLteDb.releaseThreadConnection();
        }        
    }

    // ── Block range application ──────────────────────────────────────

    private void applyRange(Connection conn, int from, int to) throws Exception {
        for (int h = from; h <= to; h++) {
            String   hash  = rpc.getBlockHash(h);
            BlockData block = rpc.getBlockDataVerbose(hash);
            applyBlock(conn, block);

            long total = blocksApplied.incrementAndGet();
            if (total % BATCH_LOG_INTERVAL == 0) {
                AppLogger.info("[ChainIndexer] … {} blocks applied (height {})", total, h);
            }
        }
    }

    // ── Single block application (atomic) ───────────────────────────

    /**
     * Writes all five tables for one block in a single DB transaction.
     * If anything fails the whole block is rolled back.
     */
    // ── ChainIndexer.java — applyBlock without lambda ────────────────────

    private void applyBlock(Connection conn, BlockData block) throws Exception {
            // 1. insert block header
            insertBlock(conn, block);

            // 2. insert every transaction + its inputs + outputs + utxos
            for (int txIdx = 0; txIdx < block.transactions().size(); txIdx++) {
                TxData tx = block.transactions().get(txIdx);
                applyTransaction(conn, tx, block, txIdx);
            }

            // 3. advance chain tip
            setChainTip(conn, block.hash(), block.height());

            //conn.commit();
            AppLogger.info("[ChainIndexer] Block {} committed ({} txs)",
                block.height(), block.transactions().size());
   
    }
    
    // ── insertBlock ───────────────────────────────────────────────────────

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

    // ── applyTransaction ──────────────────────────────────────────────────

    private void applyTransaction(Connection conn,  
                                   TxData     tx,
                                   BlockData  block,
                                   int        txIdx) throws Exception {
        boolean isCoinbase = isCoinbase(tx);
        long    fee        = isCoinbase ? 0L : computeFee(conn, tx);
        
        insertTransaction(conn, tx, block, txIdx, isCoinbase, fee);
        insertInputs     (conn, tx, block.height(), isCoinbase);
        insertOutputs    (conn, tx, block.height(), isCoinbase);
    }

    // ── insertTransaction ─────────────────────────────────────────────────

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

    // ── insertInputs ──────────────────────────────────────────────────────

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

    // ── insertOutputs ─────────────────────────────────────────────────────

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
                    int chainTip = store.getChainTipHeight(conn);
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

    // ── markUtxoSpent ─────────────────────────────────────────────────────

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

    // ── setChainTip ───────────────────────────────────────────────────────

    private void setChainTip(Connection conn, String hash, int height)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chain_tip (id, hash, height) VALUES (1, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE " +
                        "SET hash=excluded.hash, height=excluded.height")) {
            ps.setString(1, hash);
            ps.setInt   (2, height);
            ps.executeUpdate();
            AppLogger.info("[ChainIndexer] chain_tip → height={} hash={}",
                height, hash.substring(0, Math.min(12, hash.length())));
        }
    }

    // ── computeFee ────────────────────────────────────────────────────────

    private long computeFee(Connection conn, TxData tx) throws SQLException {
        long inputTotal = 0L;
        for (TxInputData vin : tx.inputs()) {
            if (vin.isCoinbase()) return 0L;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value_sat FROM transaction_outputs " +
                            "WHERE txid=? AND vout=?")) {
                ps.setString(1, vin.prevTxId());
                ps.setInt   (2, vin.prevVout());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        AppLogger.warn("[ChainIndexer] Prev output {}:{} not found — fee=0",
                            vin.prevTxId(), vin.prevVout());
                        return 0L;
                    }
                    inputTotal += rs.getLong("value_sat");
                }
            }
        }
        long outputTotal = tx.outputs().stream()
            .mapToLong(o -> BlockDataMapper.toSats(BigDecimal.valueOf(o.valueSat())))
            .sum();
        return Math.max(0L, inputTotal - outputTotal);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isCoinbase(TxData tx) {
        return !tx.inputs().isEmpty() && tx.inputs().get(0).isCoinbase();
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