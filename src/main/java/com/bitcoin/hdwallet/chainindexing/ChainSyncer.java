package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author CONALDES
 */


import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.chaindata.StoredBlock;
import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.inputcontrol.SharedMonitor;
import java.util.concurrent.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;


public class ChainSyncer implements Runnable {

    private final BitcoinRpcClient rpc;
    private final ChainIndexRepository  store;
    private final ChainDatabase sqLteDb;
    
    private final ReorgDetector reorgDetector;
    
    private volatile int lastKnownTip = -1;
    private final SharedMonitor monitor;
    private final int id = 2;

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chain-syncer");
            t.setDaemon(true);
            return t;
        });

    public ChainSyncer(BitcoinRpcClient rpc,
                       ChainDatabase sqLteDb,
                       ChainIndexRepository store,
                       SharedMonitor monitor) {
        this.rpc    = rpc;
        this.sqLteDb   = sqLteDb;
        this.store  = store;
        this.monitor = monitor;
        this.reorgDetector = new ReorgDetector(rpc, sqLteDb);
    }
    
    @Override
    public void run() {
        try {
            //catchUp();
            while (true) {
                try {
                    // 1. Wait for signal from Controller
                    monitor.waitForSignal(id);
                    
                    // 2. Perform the task
                    AppLogger.info("ChainSyncer: Executing task...");
                    tick();
                    //Thread.sleep(1500); // Simulate work
                    AppLogger.info("ChainSyncer: Task finished.");
                    
                    // 3. Update shared variables to indicate free
                    monitor.markWorkerFree(id);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    System.getLogger(ChainSyncer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                }
            }
        } catch (Exception ex) {
            System.getLogger(ChainSyncer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
   
    public void stop() {
        AppLogger.info("Stopping Chain sync scheduler...");
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

    // ───────────────────────────────────────────────────────────────
    // 🔁 TICK
    // ───────────────────────────────────────────────────────────────

    private void tick() throws Exception {

        // 1. Reorg handling
        //ReorgDetector detector = new ReorgDetector(rpc, sqLteDb);
        ReorgDetector.ReorgResult reorg = reorgDetector.detectAndRecover();

        if (reorg.reorgOccurred() && !reorg.success()) {
            AppLogger.error("[ChainSyncer] Reorg failed — skipping tick");
            return;
        }

        // 2. Continue syncing
        catchUp();
    }

    // ChainSyncer.java — decide where to start syncing from

    public void catchUp() throws Exception {
        Connection conn = sqLteDb.getConnection();
        try {
            int localHeight = store.getLastHeightOrMinusOne(conn);
            int coreHeight  = rpc.getBlockCount();

            if (localHeight >= coreHeight) {
                AppLogger.info("[ChainSyncer] Already at tip height={}", localHeight);
                return;
            }

            int from  = localHeight + 1;   // -1 + 1 = 0 on first run
            int total = coreHeight - localHeight;

            AppLogger.info("[ChainSyncer] Syncing {} block(s) [{} → {}]",
                total, from, coreHeight);

            //for (int h = from; h <= coreHeight; h++) {
            //    store.applyHeight(conn, h);
            //}
            applyRange(conn, from, coreHeight);
        } catch (SQLException e) {
            AppLogger.error("[Sync] Error: {}", e.getMessage());
            throw e;
        }

        finally {
            // Pattern A — do NOT close, ThreadLocal keeps it alive
            // Pattern B — pool.release(conn)
            // Pattern C — end of synchronized block
        }
    }

    // ───────────────────────────────────────────────────────────────
    // ⚡ APPLY RANGE (BATCHED)
    // ───────────────────────────────────────────────────────────────

    public void applyRange(Connection conn, int from, int to) throws Exception {

        final int BATCH_SIZE = 50;

        for (int start = from; start <= to; start += BATCH_SIZE) {

            int end = Math.min(start + BATCH_SIZE - 1, to);

            // 🚀 1. Fetch hashes in batch
            List<String> hashes = fetchHashes(start, end);
            
            for (int i = 0; i < hashes.size(); i++) {

                int height = start + i;
                String hash = hashes.get(i);

                BlockData block = rpc.getBlockDataVerbose(hash);

                validateChainLink(height, block);
                
                store.insertBlock(conn, block);
                //writer.writeBlock(conn, block);
                if ((height - from + 1) % 100 == 0) {
                   AppLogger.info("[ChainSyncer] … height {}", height);
                }
            }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // 🔗 VALIDATION
    // ───────────────────────────────────────────────────────────────

    private void validateChainLink(int height, BlockData block) throws Exception {

        if (height == 0) return;
        Connection conn = sqLteDb.getConnection();
        try {
        StoredBlock prev = store.getBlockAtHeight(conn, height - 1);

        if (prev != null && !prev.hashEquals(block.prevHash())) {
            throw new IllegalStateException(
                "Chain break at height " + height +
                ": our prev=" + prev.hash() +
                " core says=" + block.prevHash());
        }
        } catch (SQLException e) {
            AppLogger.error("[Sync] Error: {}", e.getMessage());
            throw e;
        }
    }

    // ───────────────────────────────────────────────────────────────
    // 🚀 FETCH HASHES (THIS WAS MISSING PIECE)
    // ───────────────────────────────────────────────────────────────

    private List<String> fetchHashes(int from, int to) throws Exception {

        List<String> hashes = new ArrayList<>(to - from + 1);

        for (int h = from; h <= to; h++) {
            hashes.add(rpc.getBlockHash(h));
        }

        return hashes;
    }     
}
