package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class FeeEstimator {

    private final BitcoinRpcClient rpc;
    private boolean isRegtest;
    
    private BigDecimal fallbackFeeRateSatPerVb; // e.g., 1 sat/vB
    
    // Cached fee rates
    private final Map<Integer, CachedFee> feeCache = new ConcurrentHashMap<>();
    
    // Fallback fee rates (sat/vB)
    private static final BigDecimal FALLBACK_FEE = new BigDecimal("1.0");
    private static final BigDecimal MIN_RELAY_FEE = new BigDecimal("0.1");
    private static final BigDecimal MAX_FEE = new BigDecimal("1000.0");
    
    // Regtest specific
    private static final BigDecimal REGTEST_MIN_FEE = new BigDecimal("1.0");
    private static final BigDecimal REGTEST_FALLBACK_FEE = new BigDecimal("1.0");

    // Confirmation targets in blocks
    public static final int TARGET_1_BLOCK = 1;
    public static final int TARGET_3_BLOCKS = 3;
    public static final int TARGET_6_BLOCKS = 6;
    public static final int TARGET_12_BLOCKS = 12;
    public static final int TARGET_144_BLOCKS = 144;

    public FeeEstimator(BitcoinRpcClient rpc, boolean isRegtest) {
        this.rpc = rpc;
        this.isRegtest = isRegtest;
    }

    public FeeEstimator(BitcoinRpcClient rpc, BigDecimal fallbackFeeRateSatPerVb) {
        this.rpc = rpc;
        this.fallbackFeeRateSatPerVb = fallbackFeeRateSatPerVb;
    }

    /**
     * Estimate feerate in sat/vB for the given confirmation target.
     * On regtest/testnet or low-history nodes, falls back to configured value.
     * @param targetBlocks
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public BigDecimal estimateFeeRate(int targetBlocks) throws BitcoinRpcException {
        // estimatesmartfee conf_target
        Object res = rpc.executeRpc("estimatesmartfee", targetBlocks);

        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException("estimatesmartfee: unexpected result type: " + res.getClass());
        }

        if (obj.isNull("feerate")) {
            // No estimate -> fallback
            return fallbackFeeRateSatPerVb;
        }

        // feerate is in BTC/kvB
        BigDecimal btcPerKvB = obj.getBigDecimal("feerate");

        // Convert BTC/kvB -> sat/vB: (BTC * 1e8 sat/BTC) / 1000 vB/kvB
        BigDecimal satPerVb = btcPerKvB
                .multiply(BigDecimal.valueOf(100_000_000L))
                .divide(BigDecimal.valueOf(1000L), 0, RoundingMode.UP);

        // Never go below fallback
        return satPerVb.max(fallbackFeeRateSatPerVb);
    }

    /**
     * Estimate fee rate in sat/vB for given confirmation target
     */
    public BigDecimal estimateFee(int targetBlocks) throws BitcoinRpcException, Exception {
        if (isRegtest) {
            return estimateRegtestFee();
        }

        // Check cache first
        CachedFee cached = feeCache.get(targetBlocks);
        if (cached != null && !cached.isExpired()) {
            return cached.getFeeRate();
        }

        // Try estimatesmartfee
        BigDecimal feeRate = tryEstimateSmartFee(targetBlocks);
        if (feeRate != null) {
            cacheFee(targetBlocks, feeRate);
            return feeRate;
        }

        // Try estimaterawfee
        feeRate = tryEstimateRawFee(targetBlocks);
        if (feeRate != null) {
            cacheFee(targetBlocks, feeRate);
            return feeRate;
        }

        // Try mempool analysis
        feeRate = estimateFromMempool(targetBlocks);
        if (feeRate != null) {
            cacheFee(targetBlocks, feeRate);
            return feeRate;
        }

        // Use fallback
        return FALLBACK_FEE;
    }

    /**
     * Get fee rate with economy/min settings
     * @param targetBlocks
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public FeeEstimate getFeeEstimate(int targetBlocks) throws BitcoinRpcException, Exception {
        BigDecimal feeRate = estimateFee(targetBlocks);
        BigDecimal minFee = isRegtest ? REGTEST_MIN_FEE : MIN_RELAY_FEE;
        
        // Ensure minimum fee
        if (feeRate.compareTo(minFee) < 0) {
            feeRate = minFee;
        }
        
        // Cap maximum fee
        if (feeRate.compareTo(MAX_FEE) > 0) {
            feeRate = MAX_FEE;
        }
        
        return new FeeEstimate(feeRate, minFee, !isUsingFallback());
    }

    /**
     * Calculate transaction fee in satoshis
     */
    public long calculateFee(int vsize, int targetBlocks) throws BitcoinRpcException, Exception {
        BigDecimal feeRate = estimateFee(targetBlocks);
        return feeRate.multiply(BigDecimal.valueOf(vsize))
                      .setScale(0, RoundingMode.CEILING)
                      .longValue();
    }

    /**
     * Get fee rate for urgent transaction (1 block)
     */
    public BigDecimal getUrgentFee() throws BitcoinRpcException, Exception {
        return estimateFee(TARGET_1_BLOCK);
    }

    /**
     * Get fee rate for normal transaction (6 blocks)
     */
    public BigDecimal getNormalFee() throws BitcoinRpcException, Exception {
        return estimateFee(TARGET_6_BLOCKS);
    }

    /**
     * Get fee rate for economical transaction (144 blocks)
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public BigDecimal getEconomyFee() throws BitcoinRpcException, Exception {
        return estimateFee(TARGET_144_BLOCKS);
    }

    // ============ Private Methods ============

    private BigDecimal tryEstimateSmartFee(int targetBlocks) throws Exception {
        try {
            JSONArray params = new JSONArray(); 
            params.put(targetBlocks);
            params.put("ECONOMICAL");
            
            Object result = rpc.executeRpc("estimatesmartfee", params);
            
            JsonNode jsonresult = (JsonNode) result;
            if (jsonresult.has("feerate") && !jsonresult.get("feerate").isNull()) {
                // Convert BTC/kB to sat/vB
                BigDecimal btcPerKb = jsonresult.get("feerate").decimalValue();
                return btcPerKb.multiply(BigDecimal.valueOf(100_000))
                               .divide(BigDecimal.valueOf(1000), 2, RoundingMode.CEILING);
            }
            
            // Check for errors
            if (jsonresult.has("errors") && jsonresult.get("errors").size() > 0) {
                String error = jsonresult.get("errors").get(0).asText();
                if (error.contains("Insufficient data") || error.contains("no fee estimates")) {
                    return null;
                }
            }
            
            return null;
        } catch (BitcoinRpcException e) {
            return null;
        }
    }

    private BigDecimal tryEstimateRawFee(int targetBlocks) throws Exception {
        try {
            // Try with different modes
            String[] modes = {"ECONOMICAL", "CONSERVATIVE"};
            
            JSONArray params = new JSONArray(); 
            for (String mode : modes) {
                params.put(targetBlocks);
                params.put(0.95);
                params.put(mode);
                Object result = rpc.executeRpc("estimaterawfee", params);
                
                JsonNode jsonresult = (JsonNode) result;
                if (jsonresult.has("feerate") && !jsonresult.get("feerate").isNull()) {
                    BigDecimal btcPerKb = jsonresult.get("feerate").decimalValue();
                    return btcPerKb.multiply(BigDecimal.valueOf(100_000))
                                   .divide(BigDecimal.valueOf(1000), 2, RoundingMode.CEILING);
                }
                
                // Try short/medium/long horizon
                String[] horizons = {"SHORT", "MEDIUM", "LONG"};
                for (String horizon : horizons) {
                    if (jsonresult.has(horizon) && jsonresult.get(horizon).has("feerate") && 
                        !jsonresult.get(horizon).get("feerate").isNull()) {
                        BigDecimal btcPerKb = jsonresult.get(horizon).get("feerate").decimalValue();
                        return btcPerKb.multiply(BigDecimal.valueOf(100_000))
                                       .divide(BigDecimal.valueOf(1000), 2, RoundingMode.CEILING);
                    }
                }
            }
            
            return null;
        } catch (BitcoinRpcException e) {
            return null;
        }
    }

    private BigDecimal estimateFromMempool(int targetBlocks) throws Exception {
        try {
            JSONArray params = new JSONArray(); 
            params.put(targetBlocks);
            Object mempoolInfo = rpc.executeRpc("getmempoolinfo", params);
            
            JsonNode jsonresult = (JsonNode) mempoolInfo;
            if (!jsonresult.has("mempoolminfee")) {
                return null;
            }
            
            BigDecimal minFee = jsonresult.get("mempoolminfee").decimalValue();
            
            // If mempool is small, use min fee
            long mempoolSize = jsonresult.has("bytes") ? jsonresult.get("bytes").asLong() : 0;
            if (mempoolSize < 100_000) { // < 100KB
                return minFee.multiply(BigDecimal.valueOf(100_000))
                             .divide(BigDecimal.valueOf(1000), 2, RoundingMode.CEILING);
            }
            
            // For larger mempool, estimate based on target
            // Simple heuristic: multiply min fee by factor based on target
            double factor = targetBlocks <= 3 ? 2.0 : 
                           targetBlocks <= 6 ? 1.5 : 
                           targetBlocks <= 12 ? 1.2 : 1.0;
            
            return minFee.multiply(BigDecimal.valueOf(factor * 100_000))
                         .divide(BigDecimal.valueOf(1000), 2, RoundingMode.CEILING);
            
        } catch (BitcoinRpcException e) {
            return null;
        }
    }

    private BigDecimal estimateRegtestFee() {
        // In regtest, blocks are mined on demand
        // Use minimum fee to ensure relay
        return REGTEST_FALLBACK_FEE;
    }

    private boolean isUsingFallback() {
        // Check if all recent estimates used fallback
        return feeCache.values().stream()
                      .allMatch(c -> c.getFeeRate().compareTo(FALLBACK_FEE) == 0);
    }

    private void cacheFee(int targetBlocks, BigDecimal feeRate) {
        feeCache.put(targetBlocks, new CachedFee(feeRate, TimeUnit.MINUTES.toMillis(5)));
    }

    /**
     * Clear fee cache
     */
    public void clearCache() {
        feeCache.clear();
    }

    // ============ Inner Classes ============

    private static class CachedFee {
        private final BigDecimal feeRate;
        private final long expiryTime;

        public CachedFee(BigDecimal feeRate, long ttlMillis) {
            this.feeRate = feeRate;
            this.expiryTime = System.currentTimeMillis() + ttlMillis;
        }

        public BigDecimal getFeeRate() { return feeRate; }
        public boolean isExpired() { return System.currentTimeMillis() > expiryTime; }
    }

    public static class FeeEstimate {
        private final BigDecimal feeRate;
        private final BigDecimal minFee;
        private final boolean isEstimated;

        public FeeEstimate(BigDecimal feeRate, BigDecimal minFee, boolean isEstimated) {
            this.feeRate = feeRate;
            this.minFee = minFee;
            this.isEstimated = isEstimated;
        }

        public BigDecimal getFeeRate() { return feeRate; }
        public BigDecimal getMinFee() { return minFee; }
        public boolean isEstimated() { return isEstimated; }
        public boolean isFallback() { return !isEstimated; }

        @Override
        public String toString() {
            return String.format("FeeEstimate{rate=%s sat/vB, min=%s sat/vB, %s}", 
                    feeRate, minFee, isEstimated ? "estimated" : "fallback");
        }
    }
}
