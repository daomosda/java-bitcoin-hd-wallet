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
  
    private static final int MAX_REORG_DEPTH = 100;  // cap
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private volatile boolean running = false;

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
                AppLogger.info("UtxoSyncManager: Executing task...");
                //Thread.sleep(1500); // Simulate work
                
                SyncResult result = performSync();
                AppLogger.info("[UtxoScheduler] Sync complete:" 
                        + " height={} utxos={} sat={} updated={}", 
                        result.getBlockHeight(),
                        result.getTotalUtxos(),
                        result.getTotalValue(),
                        result.getUpdatedUtxos());
                
                AppLogger.info("UtxoSyncManager: Task finished.");

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

        AppLogger.info("Stopping UTXO sync scheduler...");

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
            AppLogger.warn("Sync already in progress, skipping runOnce()");
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
            AppLogger.info("[Sync] Resuming from height={}", lastHeight);
        } catch (SQLException e) {
            AppLogger.error("[Sync] Error: {}", e.getMessage());
            throw e;
        }

        int tip = walletRpc.getCoreTipHeight();

        int gap = (lastHeight < 0) ? Integer.MAX_VALUE : (tip - lastHeight);

        AppLogger.info("Sync decision: lastHeight={}, tip={}, gap={}", lastHeight, tip, gap);

        // 🚀 Decision logic
        if (lastHeight < 0) {
            AppLogger.info("No previous sync → running full block sync");
            syncFromCore();
            return syncOnce(); // refresh UTXO view after
        }

        if (gap > BLOCK_SYNC_THRESHOLD) {
            AppLogger.info("Gap {} > threshold {} → running block sync", gap, BLOCK_SYNC_THRESHOLD);
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

        AppLogger.info("Block sync: {} → {}", lastHeight, tip);
        if (lastHeight == tip) {
            AppLogger.info("Already at tip — skipping sync");
            return;
        }

        dbIndexStore.DBConn().beginTransaction(conn);
        for (int height = lastHeight + 1; height <= tip; height++) {

            String hash = (String) walletRpc.executeRpc("getblockhash", height);
            JSONObject blockJson =
                (JSONObject) walletRpc.executeRpc("getblock", hash, 2);

            BlockData block = BlockDataMapper.mapBlock(blockJson);

            // ✅ process block + UTXO together
            processBlockWithUtxo(conn, block, height);

            if (height % 100 == 0) {
                AppLogger.info("Processed block {}", height);
            }
        }
        AppLogger.info("Block sync complete up to height {}", tip);
        dbIndexStore.DBConn().commit(conn);
        } catch (SQLException e) {
            dbIndexStore.DBConn().rollback(conn);
            throw e;
        } finally {
            // ALWAYS release — runs whether we return, throw, or complete normally
            dbIndexStore.DBConn().releaseThreadConnection();
        }
        //AppLogger.info("Block sync complete up to height {}", tip);
    }
      
    private void processBlockWithUtxo(Connection conn, BlockData block, int height) throws Exception {

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
    
    //###################################################################################
    //###################################################################################
    public void syncWalletUtxos() throws Exception {

        Object raw = walletRpc.executeRpc("listunspent");

        if (raw == null) {
            AppLogger.warn("listunspent returned null");
            return;
        }

        JSONArray utxos = (JSONArray) raw;

        AppLogger.info("Syncing {} wallet UTXOs", utxos.length());

        for (int i = 0; i < utxos.length(); i++) {

            JSONObject u = utxos.getJSONObject(i);

            String txid = u.getString("txid");
            int vout = u.getInt("vout");

            double amountBtc = u.getDouble("amount");
            long valueSat = (long) (amountBtc * 100_000_000L);

            String scriptHex = u.getString("scriptPubKey");

            String key = txid + ":" + vout;

            AppLogger.info("[WalletUTXO] {} -> {} BTC", key, amountBtc);
        }
    }

    public SyncResult syncOnce() throws Exception {
        long startTime = System.currentTimeMillis();
        SyncResult result = new SyncResult();

        AppLogger.info("Starting UTXO sync...");

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
            AppLogger.info(String.format("Blocks advanced by %d (from %d to %d)",
                    blocksToScan, lastSyncHeight, currentHeight));
        } else if (lastSyncHeight == 0) {
            AppLogger.info("First sync - expecting empty or initial UTXO set.");
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

        AppLogger.info(String.format(
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

        AppLogger.info("[UTXO] Received funds on {}", address);

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
}