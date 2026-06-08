package com.bitcoin.hdwallet.chainindexing;

import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import java.util.Objects;

import java.sql.*;

/**
 *
 * @author DAOMOSDA
 */

public class ChainReorgHandler {

    private static final int MAX_REORG_DEPTH = 100;  // cap

    private final ChainIndexRepository chainStore;

    public ChainReorgHandler(ChainIndexRepository chainStore) {
        this.chainStore = chainStore;
    }
    
    
    public void ensureOnMainChain(Connection conn, int lastHeight) throws SQLException, BitcoinRpcException, Exception {
        if (lastHeight < 0) {
            return; // nothing indexed yet
        }
        //try {
        String localHash = chainStore.getBlockHash(conn, lastHeight);
        if (localHash == null) {
            // DB is missing this height; treat as needing full resync
            chainStore.rollbackToHeight(conn, 0);
            return;
        }
        
        String coreHash  = (String) BitcoinRpcClient.executeRpc("getblockhash", lastHeight);

        if (!Objects.equals(localHash, coreHash)) {

            AppLogger.info("Reorg detected at height {}", lastHeight);

            chainStore.deleteBlocksAboveHeight(conn, lastHeight - 1);
        }       
    }
   
    private void rollbackToCommonAncestorWithDepthCap(int startHeight) throws Exception {
        int h = startHeight;
        int steps = 0;

        while (h >= 0) {
            if (steps > MAX_REORG_DEPTH) {
                // Cap exceeded: do full reindex from height 0
                System.out.printf("Reorg deeper than {} blocks (from {}). " +
                               "Rolling back to height 0 (full reindex).",
                               MAX_REORG_DEPTH, startHeight);
                chainStore.standAlonerollbackToHeight(0);
                return;
            }

            String localHash = chainStore.getBlockHashAtHeight(h);
            if (localHash == null) {
                h--;
                steps++;
                continue;
            }

            String coreHash = (String) BitcoinRpcClient.executeRpc("getblockhash", h);

            if (coreHash.equals(localHash)) {
                // Found common ancestor
                int deleteFrom = h + 1;
                int depth = startHeight - h;
                System.out.printf("Common ancestor at height {}, depth={}, hash={}",
                               h, depth, localHash);
                chainStore.standAlonerollbackToHeight(deleteFrom);
                return;
            }

            h--;
            steps++;
        }

        // Fell out of loop without match: no ancestor (paranoid safety)
        AppLogger.warn("No common ancestor found (even after depth cap). Full reindex.");
        chainStore.standAlonerollbackToHeight(0);
    }
}