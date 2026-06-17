/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chainindexing.BlockDataMapper;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.chainindexing.ChainReorgHandler;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.inputcontrol.SharedMonitor;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import com.bitcoin.hdwallet.model.Utxo;
import com.bitcoin.hdwallet.utxosinfo.UtxoAnalyzer;
import com.bitcoin.hdwallet.utxosinfo.UtxoSet;
import com.bitcoin.hdwallet.utxosinfo.UtxoSummary;
import java.io.IOException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import java.sql.*;

/**
 * Periodic Utxo sync against a watch-only descriptor wallet in Bitcoin Core.
 */
public final class UtxoSyncManager implements Runnable {
    
    private static String className = "UtxoSyncManager";
  
    private static final int MAX_REORG_DEPTH = 100;  // cap
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private volatile boolean running = false;
    
    // ── NEW field ──────────────────────────────────────────────────────────
    /**
     * When true: no scheduled polling — only explicit catchUp() calls.
     * Set by startCatchUpOnlyMode() after ZMQ takes over.
     */
    private volatile boolean catchUpOnlyMode  = false;

    static final long INITIAL_DELAY_SEC = 30; // or 60, whatever you like
    private static final long SYNC_INTERVAL_SEC = 240; // 4 minutes
    private static final int BLOCK_SYNC_THRESHOLD = 10; // configurable

    private final BitcoinRpcClient walletRpc;    
    private final HdAddressManager addressManager;
    private volatile long lastSyncHeight = 0;
    private volatile long lastSyncTime = 0;
    
    private final ChainDatabase sqLteDb;
    
    private final ChainIndexRepository dbIndexStore;
    private Set<String> addressCache = ConcurrentHashMap.newKeySet();
    private final SharedMonitor monitor;
    private final int id = 1;
    
    //private final ReentrantLock processLock = new ReentrantLock();
    
    public UtxoSyncManager(BitcoinRpcClient walletRpc, HdAddressManager addressManager, 
            ChainIndexRepository dbIndexStore, ChainDatabase sqLteDb, 
            Set<String> addressCache, SharedMonitor monitor) {
        this.walletRpc = walletRpc;
        this.addressManager = addressManager;
        this.dbIndexStore = dbIndexStore;
        this.sqLteDb = sqLteDb;
        this.addressCache = addressCache;
        this.monitor = monitor;
    }       
    
    @Override
    public void run() {
        while (true) {
            try {
                // 1. Wait for signal from Controller
                // This method blocks until notify() is called and condition is met
                monitor.waitForSignal(id);

                // 2. Perform the task
                AppLogger.info(className, "UtxoSyncManager: Executing task...");
                //Thread.sleep(1500); // Simulate work
                
                SyncResult result = performSync();
                AppLogger.info(className, "[UtxoScheduler] Sync complete:" 
                        + " height={} utxos={} sat={} updated={}", 
                        result.getBlockHeight(),
                        result.getTotalUtxos(),
                        result.getTotalValue(),
                        result.getUpdatedUtxos());
                
                AppLogger.info(className, "UtxoSyncManager: Task finished.");

                // 3. Update shared variables to indicate free
                monitor.markWorkerFree(id);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                System.getLogger(UtxoSyncManager.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }
    
    public void stop() {

        if (!running) return;

        AppLogger.info(className, "Stopping UTXO sync scheduler...");

        running = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
        
    public SyncResult runOnce() throws Exception {

        if (!syncing.compareAndSet(false, true)) {
            AppLogger.warn(className, "Sync already in progress, skipping runOnce()");
            return null;
        }

        try {
            return performSync();   // ✅ correct
        } finally {
            syncing.set(false);
        }
    }
        
    public SyncResult performSync() throws Exception {
        Connection conn = dbIndexStore.DBConn().getConnection();   // thread's own connection
        int lastHeight;
        
        try {
            lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
            AppLogger.info(className, "[Sync] Resuming from height={}", lastHeight);
        } catch (SQLException e) {
            AppLogger.error("[Sync] Error: {}", e.getMessage());
            throw e;
        }

        int tip = walletRpc.getCoreTipHeight();

        int gap = (lastHeight < 0) ? Integer.MAX_VALUE : (tip - lastHeight);

        AppLogger.info(className, "Sync decision: lastHeight={}, tip={}, gap={}", lastHeight, tip, gap);

        // 🚀 Decision logic
        if (lastHeight < 0) {
            AppLogger.info(className, "No previous sync → running full block sync");
            syncFromCore();
            return syncOnce(); // refresh UTXO view after
        }

        if (gap > BLOCK_SYNC_THRESHOLD) {
            AppLogger.info(className, "Gap {} > threshold {} → running block sync", gap, BLOCK_SYNC_THRESHOLD);
            syncFromCore();
            return syncOnce();
        }
        
        // Normal fast path
        return syncOnce();
    }
        
    public void syncFromCore() throws Exception {
        Connection conn = dbIndexStore.DBConn().getConnection();
        try{
            
        int lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);

        ChainReorgHandler reorgHandler =
            new ChainReorgHandler(dbIndexStore);

        reorgHandler.ensureOnMainChain(conn, lastHeight);

        lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
        int tip = walletRpc.getCoreTipHeight();

        AppLogger.info(className, "Block sync: {} → {}", lastHeight, tip);
        if (lastHeight == tip) {
            AppLogger.info(className, "Already at tip — skipping sync");
            return;
        }

        dbIndexStore.DBConn().beginTransaction(conn);
        for (int height = lastHeight + 1; height <= tip; height++) {

            String hash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);
            JSONObject blockJson =
                (JSONObject) BitcoinRpcClient.executeRpc("getblock", hash, 2);

            BlockData block = BlockDataMapper.mapBlock(blockJson);  
            
            //processLock.lock();
            //try {
            // ✅ process block + UTXO together
            processBlockWithUtxo(conn, block, height);
            //} finally {
            //    processLock.unlock();
            //}
            if (height % 100 == 0) {
                AppLogger.info(className, "Processed block {}", height);
            }
        }
        AppLogger.info(className, "Block sync complete up to height {}", tip);
        dbIndexStore.DBConn().commit(conn);
        } catch (SQLException e) {
            dbIndexStore.DBConn().rollback(conn);
            throw e;
        } finally {
            // ALWAYS release — runs whether we return, throw, or complete normally
            dbIndexStore.DBConn().releaseThreadConnection();
        }
        //AppLogger.info(className, "Block sync complete up to height {}", tip);
    }
      
    public void processBlockWithUtxo(Connection conn, BlockData block, int height) throws Exception {
            
            dbIndexStore.processBlock(conn, height, block);

            int chainTip = dbIndexStore.getChainTipHeight(conn);

            //int count = 0;
            for (TxData tx : block.transactions()) {

                // 🔻 Mark spent
                if (!tx.isCoinbase()) {
                    for (TxInputData in : tx.inputs()) {
                        dbIndexStore.markUtxoSpent(conn, 
                                in.prevTxId(), in.prevVout(),
                                tx.txid(), height
                        );
                    }
                }                
            
                // 🔺 Create UTXOs
                for (TxOutputData out : tx.outputs()) {

                    long amountSat = out.valueSat();
                    double amountBtc = amountSat / 100_000_000.0;

                    String address   = out.address();
                    String scriptHex = out.scriptHex();

                    int confirmations = (chainTip >= height)
                            ? (chainTip - height + 1)
                            : 0;

                    String descriptor = deriveDescriptor(out);
                    boolean solvable  = deriveSolvable(out);   // ✅ NEW

                    Utxo utxo = new Utxo(
                            tx.txid(),
                            out.vout(),
                            height,
                            tx.isCoinbase(),
                            confirmations,
                            address,
                            scriptHex,
                            BigDecimal.valueOf(amountBtc),
                            amountSat,
                            out.isSpendable(),
                            true,              // safe
                            descriptor,
                            solvable,          // ✅ NEW
                            false,             // spent
                            null,              // spent_by_txid
                            null               // spent_at_height
                    );
                    UtxoSet.getInstance().put(tx.txid() + ":" + out.vout(), utxo);
                    dbIndexStore.updateUtxo(conn, utxo);
                    //count++;
                }
            }  
            
    }
    
    public void updateLastSyncedHeight(int height) throws SQLException {
        String walletName = (String) CachedWalletMnemMap.getObject("WALLET_NAME");
        dbIndexStore.updateLastSyncedHeight(walletName, height);
    }
    
    public int getLastSyncedHeightOrMinusOne() throws SQLException {
        return dbIndexStore.getLastSyncedHeightOrMinusOne();
    }
    
    public int getNodeTipHeight() throws IOException, Exception {
        // e.g. call getblockcount via your RPC client
        return walletRpc.getChainTipHeight();
    }
    
    public BlockData fetchBlockViaRpc(int height) throws IOException, BitcoinRpcException {
        // 1. getblockhash(height)
        // 2. getblock(hash, 0 or 2)
        // 3. map to BlockData (same structure used by RawBlockDecoder)
        String hash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);
        JSONObject blockJson = (JSONObject) BitcoinRpcClient.executeRpc("getblock", hash, 2);

        BlockData block = BlockDataMapper.mapBlock(blockJson);  
        return block;
    }
    
    //###################################################################################
    //###################################################################################
    public void syncWalletUtxos() throws Exception {

        Object raw = walletRpc.executeRpc("listunspent");

        if (raw == null) {
            AppLogger.warn(className, "listunspent returned null");
            return;
        }

        JSONArray utxos = (JSONArray) raw;

        AppLogger.info(className, "Syncing {} wallet UTXOs", utxos.length());

        for (int i = 0; i < utxos.length(); i++) {

            JSONObject u = utxos.getJSONObject(i);

            String txid = u.getString("txid");
            int vout = u.getInt("vout");

            double amountBtc = u.getDouble("amount");
            long valueSat = (long) (amountBtc * 100_000_000L);

            String scriptHex = u.getString("scriptPubKey");

            String key = txid + ":" + vout;

            AppLogger.info(className, "[WalletUTXO] {} -> {} BTC", key, amountBtc);
        }
    }

    public SyncResult syncOnce() throws Exception {
        long startTime = System.currentTimeMillis();
        SyncResult result = new SyncResult();

        AppLogger.info(className, "Starting UTXO sync...");

        // 1) Get current block height
        Object blockchainInfo = walletRpc.executeRpc("getblockchaininfo");
        if (!(blockchainInfo instanceof JSONObject blkInfo)) {
            throw new RuntimeException("getblockchaininfo: unexpected type " + blockchainInfo.getClass());
        }

        long currentHeight = blkInfo.getLong("blocks");
        result.setBlockHeight(currentHeight);

        // 2) Optional: rescan logic (if you implement import+rescan)
        if (lastSyncHeight > 0 && currentHeight > lastSyncHeight) {
            long blocksToScan = currentHeight - lastSyncHeight;
            AppLogger.info(className, String.format("Blocks advanced by %d (from %d to %d)",
                    blocksToScan, lastSyncHeight, currentHeight));
        } else if (lastSyncHeight == 0) {
            AppLogger.info(className, "First sync - expecting empty or initial UTXO set.");
        }

        // 3) Get UTXOs from watch-only wallet
        List<Utxo> rpcUtxos = walletRpc.listUnspent(0, 9999999);
        CachedWalletMnemMap.cachedNewObject("rpcUtxos", rpcUtxos);  
      
        int tipHeight = walletRpc.getCoreTipHeight();
        
        long totalValue;
        int numOfNewUtxos = 0;
        //Connection conn = dbIndexStore.DBConn();
        //conn.setAutoCommit(false);
        Connection conn = dbIndexStore.DBConn().getConnection();
        try{
            dbIndexStore.DBConn().beginTransaction(conn);
        for (Utxo utxo : rpcUtxos) {  
            int confirmations = utxo.getConfirmations();

            int height = (confirmations > 0)
                    ? tipHeight - confirmations + 1
                    : -1; // unconfirmed
            
            dbIndexStore.updateUtxo(conn, utxo);             
        }       
                
        UtxoSummary  utxoSummary = UtxoAnalyzer.analyze(rpcUtxos);
        System.out.println("\n############################### Utxo Summary ###################################");
        System.out.println(utxoSummary.toString());
        System.out.println("##################################################################################");
        result.setTotalUtxos(rpcUtxos.size());

        totalValue = rpcUtxos.stream()
            .mapToLong(u -> u.getAmount()
                    .multiply(BigDecimal.valueOf(100_000_000L))
                    .longValueExact())
            .sum();
        
        result.setTotalValue(totalValue);
        
        boolean isNew;
        //int numOfNewUtxos = 0;
        for (Utxo u : rpcUtxos) {

            isNew = !dbIndexStore.containsUtxo(conn, u);
            
            dbIndexStore.updateUtxo(conn, u); 
                
            if (isNew) {
                onUtxoReceived(u.getAddress());
                numOfNewUtxos++;
            }
        }  
        
        dbIndexStore.DBConn().commit(conn);
        } catch (Exception e) {
            // rollback any open transaction — safe even if none is open
            dbIndexStore.DBConn().rollback(conn);
            AppLogger.error("[UtxoSync] syncFromCore failed: {}", e.getMessage());
            throw e;

        } finally {
            // ALWAYS release — runs whether we return, throw, or complete normally
            dbIndexStore.DBConn().releaseThreadConnection();
        }
             
        result.setUpdatedUtxos(numOfNewUtxos);
       
        lastSyncHeight = currentHeight;
        lastSyncTime = System.currentTimeMillis();

        long duration = lastSyncTime - startTime;
        result.setDurationMs(duration);

        AppLogger.info(className, String.format(
            "UTXO sync completed in %d ms: %d UTXOs, %d sat total, %d updated",
            duration, rpcUtxos.size(), totalValue, numOfNewUtxos));

        return result;
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
       
    public void onUtxoReceived(String address) throws Exception {

        AppLogger.info(className, "[UTXO] Received funds on {}", address);

        addressManager.markUsed(address);

        // refresh cache if needed
        addressCache.add(address);

        // refill pool
        addressManager.ensureLookahead();
    }
        
    // Simple POJO to return stats
    public static final class SyncResult {
        private long blockHeight;
        private int totalUtxos;
        private long totalValue;
        private int updatedUtxos;
        private long durationMs;

        public long getBlockHeight() { return blockHeight; }
        public void setBlockHeight(long h) { this.blockHeight = h; }

        public int getTotalUtxos() { return totalUtxos; }
        public void setTotalUtxos(int n) { this.totalUtxos = n; }

        public long getTotalValue() { return totalValue; }
        public void setTotalValue(long v) { this.totalValue = v; }

        public int getUpdatedUtxos() { return updatedUtxos; }
        public void setUpdatedUtxos(int n) { this.updatedUtxos = n; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long d) { this.durationMs = d; }
    }
    
    /**
    * Switches UtxoSyncManager from normal scheduled polling mode
    * to catch-up-only mode.
    *
    * In catch-up-only mode:
    *   - No periodic scheduled sync runs
    *   - Sync only runs when explicitly triggered via catchUp()
    *   - Used after ZMQ takes over real-time block processing
    *   - Still handles sequence gaps and missed blocks
    */
   public void startCatchUpOnlyMode() {
       AppLogger.info(className, "  Switching to catch-up-only mode."
               + " ZMQ handles real-time blocks.");

       // ── Stop the existing scheduled polling ───────────────────────────────
       if (scheduler != null && !scheduler.isShutdown()) {
           scheduler.shutdown();
           try {
               if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                   scheduler.shutdownNow();
                   AppLogger.warn(className, "  Scheduler forced shutdown.");
               }
           } catch (InterruptedException e) {
               scheduler.shutdownNow();
               Thread.currentThread().interrupt();
           }
           AppLogger.info(className, "  Scheduled polling stopped.");
       }

       // ── Mark mode ─────────────────────────────────────────────────────────
       this.catchUpOnlyMode = true;

       AppLogger.info(className, "  Catch-up-only mode active."
               + " Call catchUp() to sync missed blocks.");
   }

   // ─────────────────────────────────────────────────────────────────────────────

   /**
    * Runs a catch-up sync — processes any blocks missed since last sync.
    * Called by ZmqBlockHandler when a sequence gap is detected.
    *
    * Safe to call from any thread — guarded by syncing AtomicBoolean.
    *
    * @return SyncResult or null if sync already in progress
     * @throws java.lang.Exception
    */
   public SyncResult catchUp() throws Exception {
       if (!catchUpOnlyMode) {
           AppLogger.warn(className, "  catchUp() called but not in"
                   + " catch-up-only mode — use performSync() instead.");
           return performSync();
       }

       if (!syncing.compareAndSet(false, true)) {
           AppLogger.warn(className, "  Catch-up already in progress"
                   + " — skipping.");
           return null;
       }

       AppLogger.info(className, " ── Catch-up sync starting ────────");

       try {
           Connection conn = dbIndexStore.DBConn().getConnection();
           int lastHeight;
           try {
               lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
           } catch (SQLException e) {
               AppLogger.warn(className, " Could not read last height: "
                       + e.getMessage());
               throw e;
           } finally {
               dbIndexStore.DBConn().releaseThreadConnection();
           }

           int tip = walletRpc.getCoreTipHeight();
           int gap = (lastHeight < 0)
                   ? Integer.MAX_VALUE
                   : (tip - lastHeight);

           AppLogger.info(className, String.format(
                   " Catch-up: lastHeight=%d tip=%d gap=%d",
                   lastHeight, tip, gap));

           if (gap == 0) {
               AppLogger.info(className, " Already at tip — nothing"
                       + " to catch up.");
               return buildEmptyResult(tip);
           }

           if (gap > 0) {
               AppLogger.info(className, String.format(
                       " Catching up %d block(s) [%d → %d]",
                       gap, lastHeight, tip));
               syncFromCore();
           }

           SyncResult result = syncOnce();

           AppLogger.info(className, String.format(
                   " Catch-up complete:"
                   + " height=%d utxos=%d sat=%d updated=%d",
                   result.getBlockHeight(),
                   result.getTotalUtxos(),
                   result.getTotalValue(),
                   result.getUpdatedUtxos()));

           return result;

       } finally {
           syncing.set(false);
       }
   }

   // ─────────────────────────────────────────────────────────────────────────────

   /**
    * Checks whether any blocks have been missed since the last
    * processed height. Used by ZmqBlockHandler after a sequence gap.
    *
    * @return number of missed blocks (0 = nothing to catch up)
     * @throws java.lang.Exception
    */
   public int getMissedBlockCount() throws Exception {
       Connection conn = dbIndexStore.DBConn().getConnection();
       try {
           int lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
           int tip        = walletRpc.getCoreTipHeight();
           int missed     = Math.max(0, tip - lastHeight);

           AppLogger.debug(className, String.format(
                   " getMissedBlockCount:"
                   + " lastHeight=%d tip=%d missed=%d",
                   lastHeight, tip, missed));

           return missed;
       } finally {
           dbIndexStore.DBConn().releaseThreadConnection();
       }
   }

   // ─────────────────────────────────────────────────────────────────────────────

   /**
    * Returns true if currently in catch-up-only mode.
     * @return 
    */
   public boolean isCatchUpOnlyMode() {
       return catchUpOnlyMode;
   }

   // ─────────────────────────────────────────────────────────────────────────────

   private SyncResult buildEmptyResult(int height) {
       SyncResult result = new SyncResult();
       result.setBlockHeight(height);
       result.setTotalUtxos(0);
       result.setTotalValue(0);
       result.setUpdatedUtxos(0);
       result.setDurationMs(0);
       return result;
   }
}