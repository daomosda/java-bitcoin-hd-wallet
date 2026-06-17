/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

import com.bitcoin.hdwallet.crypto.HexUtils;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

/**
 * Mirrors a single entry from a PSBT input's "bip32_derivs" JSON array,
 * as returned by Bitcoin Core's decodepsbt RPC:
 *
 *   {
 *     "pubkey": "035b35ba...",
 *     "master_fingerprint": "a986c79f",
 *     "path": "m/84h/1h/0h/0/0"
 *   }
 *
 * Used by TrezorSigner to verify ownership of inputs before signing,
 * and to know which pubkey to attach a returned signature to.
 */
public record Bip32Deriv(
        String pubkeyHex,
        String masterFingerprint,
        String path) {

    /** Compressed pubkey as raw bytes (33 bytes). */
    public byte[] pubkeyBytes() {
        return HexUtils.hexToBytes(pubkeyHex);
    }

    /** Derivation path as Trezor-ready int[] (hardened bit set). */
    public int[] pathInts() {
        return BipPathParser.parse(path);
    }

    /**
     * Builds a Bip32Deriv from one element of decodepsbt's
     * "bip32_derivs" JSONArray.
     */
    public static Bip32Deriv fromJson(JSONObject derivJson) {
        return new Bip32Deriv(
                derivJson.getString("pubkey"),
                derivJson.getString("master_fingerprint"),
                derivJson.getString("path"));
    }

    /**
     * Convenience: extracts all Bip32Deriv entries from one PSBT
     * input's decoded JSON object (the element of decoded
     * .getJSONArray("inputs")).
     */
    public static List<Bip32Deriv> fromInputJson(JSONObject inputJson) {
        List<Bip32Deriv> result = new ArrayList<>();
        JSONArray derivs = inputJson.optJSONArray("bip32_derivs");
        if (derivs == null) return result;
        for (int i = 0; i < derivs.length(); i++) {
            result.add(fromJson(derivs.getJSONObject(i)));
        }
        return result;
    }
}