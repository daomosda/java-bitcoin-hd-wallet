package com.bitcoin.hdwallet.chaindata;

import com.bitcoin.hdwallet.core.AppLogger;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

public final class TxDataMapper {
    
    public static TxData fromJson(JSONObject json) {

        String txid   = json.getString("txid");
        int version   = json.optInt("version", 0);
        long locktime = json.optLong("locktime", 0);

        List<TxInputData> inputs  = mapInputs(json.optJSONArray("vin"));
        List<TxOutputData> outputs = mapOutputs(json);

        // 🔹 Detect coinbase
        boolean isCoinbase = !inputs.isEmpty() && inputs.get(0).isCoinbase();

        // 🔹 Size fields
        int size   = json.optInt("size", 0);
        int vsize  = json.optInt("vsize", 0);
        int weight = json.optInt("weight", 0);

        // 🔹 Raw hex
        String rawHex = json.optString("hex", "");

        // 🔹 Fee (NOT available in getblock)
        Long feeSat = null;

        return new TxData(
            txid,
            version,
            locktime,
            inputs,
            outputs,
            isCoinbase,
            size,
            vsize,
            weight,
            feeSat,
            rawHex
        );
    }
    
    public static List<TxInputData> mapInputs(JSONArray vinArray) {
        List<TxInputData> inputs = new ArrayList<>();

        if (vinArray == null) return inputs;

        for (int i = 0; i < vinArray.length(); i++) {
            JSONObject vin = vinArray.getJSONObject(i);

            boolean isCoinbase = vin.has("coinbase");
            
            String coinbaseData = null;
            if (vin.has("coinbase")) {
                coinbaseData = vin.getString("coinbase");
            }
            
            if (isCoinbase) {
                inputs.add(TxInputData.coinbase(
                    vin.getString("coinbase")
                ));
            } else {

                String txid = vin.getString("txid");
                int vout = vin.getInt("vout");

                String scriptSigHex = vin.has("scriptSig")
                    ? vin.getJSONObject("scriptSig").optString("hex", "")
                    : "";

                long sequence = vin.has("sequence")
                    ? ((Number) vin.get("sequence")).longValue()
                    : 0xFFFFFFFFL;

                List<String> witness = extractWitness(vin);
                if (!witness.isEmpty()) {
                    AppLogger.info("Witness stack size = {}", witness.size());
                }

                inputs.add(new TxInputData(
                            txid,
                            vout,
                            null,
                            scriptSigHex,
                            sequence,
                            extractWitness(vin)
                        ));   
            }
        }

        return inputs;
    }

    public static List<TxOutputData> mapOutputs(JSONObject txJson) {
        List<TxOutputData> outputs = new ArrayList<>();

        if (txJson == null) return outputs;

        String txid = txJson.getString("txid");
        JSONArray voutArray = txJson.optJSONArray("vout");

        if (voutArray == null) return outputs;

        for (int i = 0; i < voutArray.length(); i++) {
            JSONObject vout = voutArray.getJSONObject(i);

            long valueSat = (long) (vout.getDouble("value") * 100_000_000L);

            JSONObject scriptPubKey = vout.getJSONObject("scriptPubKey");

            String scriptHex  = scriptPubKey.optString("hex", "");
            String scriptType = scriptPubKey.optString("type", "");

            String address = "";
            if (scriptPubKey.has("address")) {
                address = scriptPubKey.getString("address");
            } else if (scriptPubKey.has("addresses")) {
                address = scriptPubKey.getJSONArray("addresses").optString(0, "");
            }

            outputs.add(new TxOutputData(
                txid,
                vout.getInt("n"),
                valueSat,
                scriptHex,
                scriptType,
                address
            ));
        }

        return outputs;
    }

    private static String extractAddress(JSONObject script) {
        if (script.has("address")) {
            return script.getString("address");
        }
        if (script.has("addresses")) {
            JSONArray arr = script.getJSONArray("addresses");
            return arr.length() == 0 ? "" : arr.getString(0);
        }
        return "";
    }
    
    private static List<String> extractWitness(JSONObject vin) {
        List<String> witness = new ArrayList<>();

        if (!vin.has("txinwitness")) {
            return witness; // empty → non-SegWit input
        }

        JSONArray arr = vin.optJSONArray("txinwitness");
        if (arr == null) {
            return witness;
        }

        for (int i = 0; i < arr.length(); i++) {
            String item = arr.optString(i, null);
            if (item != null && !item.isEmpty()) {
                witness.add(item);
            }
        }

        return witness;
    }
    
    public static boolean isCoinbase(JSONObject vin) {
        return vin.has("coinbase");
    }
}