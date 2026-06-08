package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.repository.ChainIndexRepository.SpentInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;

public class TransactionBroadcaster {

    //private static final Logger AppLogger = LoggerFactory.getLogger(TransactionBroadcaster.class);
    
    private final BitcoinRpcClient rpc;
    private final ChainIndexRepository dbIndexStore;
    
    // Broadcast configuration
    private int maxRetries = 3;
    private long retryDelayMs = 1000;
    private int broadcastTimeoutSeconds = 30;
    private boolean checkPropagation = true;
    private int propagationCheckDelayMs = 2000;
    private int propagationCheckRetries = 5;
    
    // Eclipse detection
    private List<String> knownPeers = new ArrayList<>();
    private int minPeersForSafeBroadcast = 3;
    private boolean eclipseCheckEnabled = true;
    
    // Recent broadcasts for double-spend protection
    private final Map<String, BroadcastRecord> recentBroadcasts = new ConcurrentHashMap<>();

    public TransactionBroadcaster(BitcoinRpcClient rpc, ChainIndexRepository dbIndexStore) {
        this.rpc = rpc;
        this.dbIndexStore = dbIndexStore;
    }

    /**
     * Broadcast transaction with full safety checks
     * @param rawTxHex
     * @param spentInputs
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException 
     * @throws java.lang.InterruptedException 
     */
    public BroadcastResult broadcast(String rawTxHex, List<SpentInput> spentInputs) 
            throws BitcoinRpcException, InterruptedException, Exception {
        
        // Pre-broadcast checks
        PreBroadcastCheck checkResult = preBroadcastChecks(rawTxHex, spentInputs);
        if (!checkResult.safe) {
            return BroadcastResult.unsafe(checkResult.reason());
        }
        
        // Attempt broadcast with retries
        BroadcastResult result = attemptBroadcast(rawTxHex);
        
        if (result.isSuccess()) {
            // Post-broadcast verification
            result = postBroadcastVerification(rawTxHex, result);
            
            // Update database
            if (result.isSuccess()) {
                updateDatabaseAfterBroadcast(rawTxHex, spentInputs);
            }
        }
        
        // Record broadcast
        recordBroadcast(rawTxHex, result);
        
        return result;
    }

    /**
     * Simple broadcast without checks
     * @param rawTxHex
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public BroadcastResult broadcastSimple(String rawTxHex) throws BitcoinRpcException, Exception {
        return attemptBroadcast(rawTxHex);
    }

    // ============ Pre-Broadcast Checks ============

    private PreBroadcastCheck preBroadcastChecks(String rawTxHex, List<SpentInput> spentInputs) 
            throws BitcoinRpcException, Exception {
        
        // 1. Check for double-spend
        DoubleSpendCheck doubleSpendResult = checkDoubleSpend(spentInputs);
        if (doubleSpendResult.isDoubleSpend()) {
            AppLogger.warn("Potential double-spend detected: {}", doubleSpendResult.details());
            return PreBroadcastCheck.createUnsafe("Double-spend detected: " + doubleSpendResult.details());
        }
        
        // 2. Check network health
        NetworkHealthCheck healthCheck = checkNetworkHealth();
        if (!healthCheck.isHealthy()) {
            if (eclipseCheckEnabled && healthCheck.isPossibleEclipse()) {
                AppLogger.warn("Possible eclipse attack detected: {}", healthCheck.details());
                return PreBroadcastCheck.createUnsafe("Network health issue: " + healthCheck.details());
            }
            AppLogger.warn("Network health issue (non-fatal): {}", healthCheck.details());
        }
        
        JSONArray params = new JSONArray();         
        params.put(rawTxHex);
        // 3. Validate transaction format
        try {
            Object testResult = rpc.executeRpc("testmempoolaccept", params);
            JsonNode jsonresult = (JsonNode) testResult;
            
            if (jsonresult.size() > 0 && jsonresult.get(0).has("allowed")) {
                if (!jsonresult.get(0).get("allowed").asBoolean()) {
                    String rejectReason = jsonresult.get(0).has("reject-reason") ?
                            jsonresult.get(0).get("reject-reason").asText() : "Unknown";
                    return PreBroadcastCheck.createUnsafe("Transaction rejected: " + rejectReason);
                }
            }
        } catch (BitcoinRpcException e) {
            AppLogger.warn("Mempool accept test failed (non-fatal): {}", e.getMessage());
        }
        
        return PreBroadcastCheck.createSafe();
    }

    private DoubleSpendCheck checkDoubleSpend(List<SpentInput> spentInputs) {
        if (spentInputs == null || spentInputs.isEmpty()) {
            return new DoubleSpendCheck(false, null);
        }
        
        // Check against recent broadcasts
        for (SpentInput input : spentInputs) {
            String outpoint = input.getTxid() + ":" + input.getVout();
            
            for (Map.Entry<String, BroadcastRecord> entry : recentBroadcasts.entrySet()) {
                if (entry.getValue().spentOutpoints().contains(outpoint)) {
                    if (entry.getValue().confirmed()) {
                        return new DoubleSpendCheck(true, 
                            "Outpoint " + outpoint + " already spent in confirmed tx " + entry.getKey());
                    } else {
                        // Check if previous tx is still in mempool
                        // This is a potential conflict
                        return new DoubleSpendCheck(true,
                            "Outpoint " + outpoint + " recently spent in tx " + entry.getKey());
                    }
                }
            }
        }
        
        return new DoubleSpendCheck(false, null);
    }

    private NetworkHealthCheck checkNetworkHealth() throws BitcoinRpcException, Exception {
        Object networkInfo = rpc.executeRpc("getnetworkinfo", new JSONArray());
        Object peerInfo = rpc.executeRpc("getpeerinfo", new JSONArray());
        
        JsonNode nwkresult = (JsonNode) networkInfo;
        JsonNode peerresult = (JsonNode) peerInfo;
        
        int connections = nwkresult.get("connections").asInt();
        boolean networkActive = peerresult.get("networkactive").asBoolean();
        
        // Update known peers
        knownPeers.clear();
        if (peerresult.isArray()) {
            for (JsonNode peer : peerresult) {
                knownPeers.add(peer.get("addr").asText());
            }
        }
        
        List<String> issues = new ArrayList<>();
        boolean possibleEclipse = false;
        
        if (connections == 0) {
            issues.add("No peer connections");
            possibleEclipse = true;
        } else if (connections < minPeersForSafeBroadcast) {
            issues.add("Low peer count: " + connections);
            possibleEclipse = true;
        }
        
        if (!networkActive) {
            issues.add("Network disabled");
        }
        
        // Check for inbound connections
        long inboundCount = 0;
        if (peerresult.isArray()) {
            for (JsonNode peer : peerresult) {
                if ("inbound".equals(peer.get("connection_type").asText())) {
                    inboundCount++;
                }
            }
        }
        
        if (inboundCount == 0 && connections > 0) {
            issues.add("No inbound connections - possible eclipse");
            possibleEclipse = true;
        }
        
        return new NetworkHealthCheck(issues.isEmpty(), possibleEclipse, 
                                      String.join("; ", issues));
    }

    // ============ Broadcast with Retries ============

    private BroadcastResult attemptBroadcast(String rawTxHex) throws BitcoinRpcException, Exception {
        BitcoinRpcException lastException = null;
        JSONArray params = new JSONArray(); 
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            params.put(rawTxHex);
            params.put(0);
            try {
                //JsonNode result = rpc.call("sendrawtransaction", rawTxHex, 
                //        0 /* maxfeerate=0 means no max */);
                Object result = rpc.executeRpc("sendrawtransaction", params);
                JsonNode jsonresult = (JsonNode) result;
                
                String txid = jsonresult.asText();
                AppLogger.info("Transaction broadcast successfully: {}", txid);
                
                return BroadcastResult.success(txid, attempt + 1);
                
            } catch (BitcoinRpcException e) {
                lastException = e;
                
                // Check if error is retryable
                if (!isRetryableBroadcastError(e)) {
                    AppLogger.error("Non-retryable broadcast error: {}", e.getMessage());
                    return BroadcastResult.failed(e.getMessage());
                }
                
                System.out.printf("Broadcast failed (attempt {}/{}): {}", 
                           attempt + 1, maxRetries, e.getMessage());
                
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelayMs * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return BroadcastResult.failed("Interrupted during retry");
                    }
                }
            }
        }
        
        return BroadcastResult.failed("Failed after " + maxRetries + " attempts: " + 
                                       (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    private boolean isRetryableBroadcastError(BitcoinRpcException e) {
        String message = e.getMessage().toLowerCase();
        
        // Non-retryable errors
        if (message.contains("invalid") || 
            message.contains("bad-txns") ||
            message.contains("insufficient fee") ||
            message.contains("dust") ||
            message.contains("non-final")) {
            return false;
        }
        
        // Retryable errors
        return message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("busy") ||
               message.contains("mempool full");
    }

    // ============ Post-Broadcast Verification ============

    private BroadcastResult postBroadcastVerification(String rawTxHex, BroadcastResult initialResult) 
            throws BitcoinRpcException, InterruptedException, Exception {
        
        if (!checkPropagation || !initialResult.isSuccess()) {
            return initialResult;
        }
        
        String txid = initialResult.getTxid();
        
        // Check if transaction is in local mempool
        for (int i = 0; i < propagationCheckRetries; i++) {
            
            try {
                Thread.sleep(propagationCheckDelayMs);               
                
                JSONArray params = new JSONArray();
                params.put(txid);
                
                Object mempoolEntry = rpc.executeRpc("getmempoolentry", params);
                JsonNode mperesult = (JsonNode) mempoolEntry;
                if (!mperesult.isNull()) {
                    AppLogger.debug("Transaction confirmed in local mempool: {}", txid);
                    return initialResult.withPropagation(true);
                }
                
            } catch (BitcoinRpcException e) {
                // Transaction might have been mined already
                if (e.getMessage().contains("not in mempool")) {
                    
                    JSONArray params = new JSONArray();
                    params.put(txid);
                    params.put(true);
                    // Check if it was mined
                    try {
                        Object tx = rpc.executeRpc("getrawtransaction", params);
                        JsonNode txresult = (JsonNode) tx;
                        if (!txresult.isNull() && txresult.has("blockhash")) {
                            AppLogger.info("Transaction already mined: {}", txid);
                            return initialResult.withMined(txresult.get("blockhash").asText());
                        }
                    } catch (BitcoinRpcException ignored) {
                    }
                }
                
                System.out.printf("Propagation check {}/{} failed: {}", 
                           i + 1, propagationCheckRetries, e.getMessage());
            }
        }
        
        // Transaction not found in mempool after retries
        System.out.printf("Transaction not found in mempool after {} checks: {}", 
                   propagationCheckRetries, txid);
        
        return initialResult.withPropagation(false);
    }

    private void updateDatabaseAfterBroadcast(String rawTxHex, List<SpentInput> spentInputs) {
        try {
            // Extract txid from hex (little-endian, first 32 bytes reversed)
            String txid = extractTxidFromHex(rawTxHex);
            
            // Mark UTXOs as spent
            dbIndexStore.markAsSpent(txid, spentInputs);
            
            AppLogger.debug("Updated database after broadcast of {}", txid);
            
        } catch (Exception e) {
            AppLogger.error("Failed to update database after broadcast", e);
            // Don't fail the broadcast for database issues
        }
    }

    private String extractTxidFromHex(String hex) {
        byte[] txBytes = hexToBytes(hex.substring(0, 64));
        reverseBytes(txBytes);
        return bytesToHex(txBytes);
    }

    private void recordBroadcast(String rawTxHex, BroadcastResult result) {
        // Extract spent outpoints from tx (simplified - would need proper parsing)
        Set<String> spentOutpoints = new HashSet<>();
        
        BroadcastRecord record = new BroadcastRecord(
            System.currentTimeMillis(),
            result.isSuccess(),
            result.isMined(),
            spentOutpoints
        );
        
        String txid = result.isSuccess() ? result.getTxid() : rawTxHex.substring(0, 64);
        recentBroadcasts.put(txid, record);
        
        // Cleanup old records (keep last hour)
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        recentBroadcasts.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }

    // ============ Utility Methods ============

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void reverseBytes(byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            byte temp = bytes[i];
            bytes[i] = bytes[bytes.length - 1 - i];
            bytes[bytes.length - 1 - i] = temp;
        }
    }

    // ============ Configuration ============

    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
    public void setCheckPropagation(boolean checkPropagation) { this.checkPropagation = checkPropagation; }
    public void setEclipseCheckEnabled(boolean eclipseCheckEnabled) { this.eclipseCheckEnabled = eclipseCheckEnabled; }
    public void setMinPeersForSafeBroadcast(int minPeers) { this.minPeersForSafeBroadcast = minPeers; }

    // ============ Inner Classes ============

    public static class BroadcastResult {
        private final boolean success;
        private final String txid;
        private final String error;
        private final boolean safe;
        private final boolean propagated;
        private final boolean mined;
        private final String blockHash;
        private final int attempts;

        private BroadcastResult(boolean success, String txid, String error, boolean safe,
                               boolean propagated, boolean mined, String blockHash, int attempts) {
            this.success = success;
            this.txid = txid;
            this.error = error;
            this.safe = safe;
            this.propagated = propagated;
            this.mined = mined;
            this.blockHash = blockHash;
            this.attempts = attempts;
        }

        public static BroadcastResult success(String txid, int attempts) {
            return new BroadcastResult(true, txid, null, true, false, false, null, attempts);
        }

        public static BroadcastResult failed(String error) {
            return new BroadcastResult(false, null, error, true, false, false, null, 0);
        }

        public static BroadcastResult unsafe(String reason) {
            return new BroadcastResult(false, null, reason, false, false, false, null, 0);
        }

        public BroadcastResult withPropagation(boolean propagated) {
            return new BroadcastResult(success, txid, error, safe, propagated, mined, blockHash, attempts);
        }

        public BroadcastResult withMined(String blockHash) {
            return new BroadcastResult(success, txid, error, safe, true, true, blockHash, attempts);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getTxid() { return txid; }
        public String getError() { return error; }
        public boolean isSafe() { return safe; }
        public boolean isPropagated() { return propagated; }
        public boolean isMined() { return mined; }
        public String getBlockHash() { return blockHash; }
        public int getAttempts() { return attempts; }

        @Override
        public String toString() {
            return String.format("BroadcastResult{success=%s, txid=%s, safe=%s, propagated=%s, mined=%s, attempts=%d}",
                    success, txid, safe, propagated, mined, attempts);
        }
    }
 
    private record PreBroadcastCheck(boolean safe, String reason) {
    
    // Rename static method to avoid confusion/naming collisions
    static PreBroadcastCheck createSafe() { 
        return new PreBroadcastCheck(true, null); 
    }

    static PreBroadcastCheck createUnsafe(String reason) { 
        return new PreBroadcastCheck(false, reason); 
    }
}

    private record DoubleSpendCheck(boolean isDoubleSpend, String details) {}

    private record NetworkHealthCheck(boolean isHealthy, boolean isPossibleEclipse, String details) {}

    private record BroadcastRecord(long timestamp, boolean success, boolean confirmed, 
                                   Set<String> spentOutpoints) {}
}