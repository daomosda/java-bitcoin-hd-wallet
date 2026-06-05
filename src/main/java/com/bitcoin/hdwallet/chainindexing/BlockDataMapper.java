package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author CONALDES
 */

// ── BlockDataMapper.java ─────────────────────────────────────────────

import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxDataMapper;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.model.Utxo;
import com.bitcoin.hdwallet.utxosinfo.UtxoSet;
import org.json.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Converts a raw org.json.JSONObject from getblock(verbosity=2)
 * into our typed BlockData / TxData / TxInput / TxOutput model.
 *
 * Called as:
 *   JSONObject raw   = rpc.getBlockVerbose2(hash);
 *   BlockData  block = BlockDataMapper.mapBlock(raw);
 */
public final class BlockDataMapper {
    private static BitcoinRpcClient nodeRpc;
    
    public BlockDataMapper(BitcoinRpcClient nodeRpc) {
        BlockDataMapper.nodeRpc = nodeRpc;
    }

    // ── Entry point ───────────────────────────────────────────────────

    /**
     * Maps a verbosity=2 getblock response into a BlockData.
     *
     * @param raw JSONObject returned by rpc.getBlockVerbose2()
     * @return 
     */
    public static BlockData mapBlock(JSONObject raw) {
        if (raw == null) {
            throw new IllegalArgumentException("mapBlock: raw JSONObject is null");
        }

        List<TxData> txList = new ArrayList<>();
        JSONArray    txArr  = raw.optJSONArray("tx");

        if (txArr == null) {
            AppLogger.warn("[BlockDataMapper] Block {} has no 'tx' array",
                raw.optString("hash", "?"));
        } else {
            for (int i = 0; i < txArr.length(); i++) {
                Object elem = txArr.get(i);
                if (elem instanceof JSONObject txObj) {
                    txList.add(mapTx(txObj));
                } else {
                    AppLogger.warn("[BlockDataMapper] tx[{}] is a String — " +
                             "use verbosity=2, not verbosity=1", i);
                }
            }
        }

        return new BlockData(
            raw.getString("hash"),
            raw.getInt   ("height"),
            raw.optString("previousblockhash", ""),   // absent on genesis
            raw.optString("merkleroot",        ""),
            raw.optLong  ("time",    0L),
            raw.optString("bits",    ""),
            raw.optLong  ("nonce",   0L),
            raw.optInt   ("version", 1),
            raw.optInt   ("size",    0),
            raw.optInt   ("weight",  0),
            txList
        );
    }

    // ── Transaction ───────────────────────────────────────────────────

    /**
     * Maps one tx object.
     *
     * {
     *   "txid":     "b39a63d...",
     *   "version":  2,
     *   "locktime": 0,
     *   "size": 155, "vsize": 128, "weight": 512,
     *   "hex":  "020000000001...",
     *   "vin":  [...],
     *   "vout": [...]
     * }
     * @param t
     * @return 
     */
    public static TxData mapTx(JSONObject t) {
        List<TxInputData>  inputs  = TxDataMapper.mapInputs (t.optJSONArray("vin"));
        List<TxOutputData> outputs = TxDataMapper.mapOutputs(t);
        boolean isCoinbase = !inputs.isEmpty() && inputs.get(0).isCoinbase();
        
        long feeSat = getFee(inputs, outputs);
        return new TxData(
            t.getString("txid"),
            t.optInt ("version",  1),
            t.optLong("locktime", 0L),
            inputs,
            outputs,
            isCoinbase,
            t.optInt("size",   0),
            t.optInt("vsize",  0),
            t.optInt("weight", 0),
            feeSat,
            t.optString("hex", null)
        );
    } 
     
    private static long getFee(List<TxInputData> inputs,
                    List<TxOutputData> outputs) {

        // Coinbase tx: no fee
        if (inputs.isEmpty() || inputs.get(0).isCoinbase()) {
            return 0L;
        }

        long inputSum = inputs.stream()
            .mapToLong(in -> {
                OptionalLong prev = null;
                prev = prevoutValueSat(in);

                if (prev.isEmpty()) {
                    AppLogger.warn("[getFee] Missing prevValue for input: {} (vout={})."
                                    + " Unable to determine fee precisely.",
                            in.prevTxId(), in.prevVout());
                    // Return 0 for this input. You could also flag the whole tx as "fee_unknown".
                    return 0L;
                }

                return prev.getAsLong();
            })
            .sum();

        long outputSum = outputs.stream()
            .mapToLong(TxOutputData::valueSat)
            .sum();

        long fee = inputSum - outputSum;

        if (fee < 0) {
            AppLogger.warn("[getFee] Negative fee detected: inputs={} sats, outputs={} sats, fee={} sats."
                            + " Clamping to 0. This usually means missing/incorrect prevout values.",
                    inputSum, outputSum, fee);
            return 0L;
        }

        return fee;
    }

    // Inside BlockDataMapper (or a helper class) 

    // Inject this (or pass as parameter) so you can RPC Core
    
    private static OptionalLong prevoutValueSat(TxInputData in) {
        String outpoint = in.prevTxId() + ":" + in.prevVout();

        Utxo utxo = UtxoSet.getInstance().get(outpoint);
        if (utxo != null && utxo.getAmountSat() != 0L) {
            return OptionalLong.of(utxo.getAmountSat());
        }

        try {
            // 1) gettxout: may return null when spent
            Object raw1 = BitcoinRpcClient.executeRpc("gettxout", in.prevTxId(), in.prevVout(), true);
            Object res1 = getRpcResult(raw1);    // raw1 must be the full JSON-RPC envelope

            if (res1 instanceof JSONObject obj1) {
                double valueBtc = obj1.getDouble("value");
                long valueSat = Math.round(valueBtc * 100_000_000L);
                return OptionalLong.of(valueSat);
            }

            // 2) If gettxout returned null, try getrawtransaction verbose=1
            Object raw2 = BitcoinRpcClient.executeRpc("getrawtransaction", in.prevTxId(), true);
            Object res2 = getRpcResult(raw2);    // again, full envelope

            if (res2 instanceof JSONObject tx) {
                JSONArray vout = tx.getJSONArray("vout");
                JSONObject out = vout.getJSONObject(in.prevVout());
                double valueBtc = out.getDouble("value");
                long valueSat = Math.round(valueBtc * 100_000_000L);
                return OptionalLong.of(valueSat);
            }

            AppLogger.info("[prevoutValue] No prevout data for {}:{} (both gettxout and getrawtransaction returned null)",
                    in.prevTxId(), in.prevVout());
        } catch (BitcoinRpcException | JSONException e) {
            AppLogger.error("[prevoutValue] Failed to fetch prevout for {}:{} - {}",
                    in.prevTxId(), in.prevVout(), e.toString());
        }

        return OptionalLong.empty();
    }
    
    private static Object getRpcResult(Object rawResponse) {
        // Normalize explicit JSON null to Java null
        if (rawResponse == null || rawResponse == JSONObject.NULL) {
            return null;
        }

        if (!(rawResponse instanceof JSONObject json)) {
            throw new RuntimeException("Unexpected RPC response type: " + rawResponse.getClass());
        }

        // JSON-RPC envelope: { "result": <any>, "error": <object|null>, "id": <any> }
        Object errorObj = json.opt("error");
        if (errorObj != null && !JSONObject.NULL.equals(errorObj)) {
            JSONObject errJson = (errorObj instanceof JSONObject)
                    ? (JSONObject) errorObj
                    : new JSONObject();
            int    code = errJson.optInt("code", 0);
            String msg  = errJson.optString("message", "Unknown RPC error");
            throw new RuntimeException("RPC error " + code + ": " + msg);
        }

        if (!json.has("result")) {
            return null;
        }

        Object result = json.get("result");
        if (result == JSONObject.NULL) {
            return null;
        }

        return result; // JSONObject, JSONArray, String, Number, Boolean
    }        
    
    // ── Value parsing ─────────────────────────────────────────────────

    public static BigDecimal parseBtcValue(JSONObject vout) {
        try {
            BigDecimal bd = vout.optBigDecimal("value", BigDecimal.ZERO);
            if (bd != null) return bd;
        } catch (Exception ignored) {}

        // fallback: read as string
        String raw = vout.optString("value", "0");
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            AppLogger.warn("[BlockDataMapper] Unparseable value '{}' — defaulting 0", raw);
            return BigDecimal.ZERO;
        }
    }
    
    public static long toSats(BigDecimal btc) {
        if (btc == null) return 0L;
        return btc.multiply(BigDecimal.valueOf(100_000_000L)).longValue();
    }
}