/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.model.Psbt;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Answers Trezor's TxRequest prompts during SignTx by building the
 * appropriate TxAck.TransactionType payload from the decoded PSBT.
 *
 * Trezor's TxAck wraps exactly one of:
 *   field 2: TransactionType {
 *     field 1: uint32 version
 *     field 2: repeated TxInputType  inputs   (embedded, wrapped as field 2)
 *     field 3: repeated TxOutputType bin_outputs / outputs
 *     field 4: uint32 lock_time
 *     field 5: uint32 inputs_cnt
 *     field 6: uint32 outputs_cnt
 *   }
 *
 * Each call to build() answers ONE TxRequest by sending exactly the
 * single input/output/meta record Trezor asked for (by requestIndex).
 *
 * Note: this implementation assumes the Psbt class exposes its decoded
 * JSON form (consistent with how OwnSigner already reads
 * decoded.getJSONArray("inputs") / .getJSONArray("outputs") from
 * Bitcoin Core's decodepsbt RPC result).
 */
public final class TxAckBuilder {

    // Trezor InputScriptType / OutputScriptType enum values
    private static final int SPENDWITNESS    = 3; // P2WPKH input
    private static final int PAYTOWITNESS    = 2; // P2WPKH/P2WSH output (bech32)
    private static final int PAYTOADDRESS    = 0; // legacy/P2SH-wrapped output

    private TxAckBuilder() {}

    /**
     * @param txReq   the decoded TxRequest Trezor just sent
     * @param decoded the full decodepsbt JSON (same shape OwnSigner uses)
     * @return raw TxAck.TransactionType protobuf bytes ready to send
     */
    public static byte[] build(
            ProtoDecoder.TxRequestResult txReq,
            JSONObject                  decoded) {

        if (txReq.wantsMeta()) {
            return buildMeta(decoded);
        }
        if (txReq.wantsInput()) {
            return buildInput(decoded, txReq.requestIndex());
        }
        if (txReq.wantsOutput()) {
            return buildOutput(decoded, txReq.requestIndex());
        }

        throw new IllegalStateException(
                "TxAckBuilder cannot answer requestType=" + txReq.requestType());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TXMETA — overall transaction metadata
    // ─────────────────────────────────────────────────────────────────────

    private static byte[] buildMeta(JSONObject decoded) {
        JSONObject tx = decoded.getJSONObject("tx");

        int version  = tx.getInt("version");
        long locktime = tx.getLong("locktime");
        int inputCount  = tx.getJSONArray("vin").length();
        int outputCount = tx.getJSONArray("vout").length();

        java.io.ByteArrayOutputStream txType = new java.io.ByteArrayOutputStream();
        writeVarintField(txType, 1, version);
        writeVarintField(txType, 4, locktime);
        writeVarintField(txType, 5, inputCount);
        writeVarintField(txType, 6, outputCount);

        return wrapTxAck(txType.toByteArray());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TXINPUT — one input record, identified by requestIndex
    // ─────────────────────────────────────────────────────────────────────

    private static byte[] buildInput(JSONObject decoded, int index) {
        JSONObject vin = decoded.getJSONObject("tx")
                .getJSONArray("vin").getJSONObject(index);

        JSONObject inputMeta = decoded.getJSONArray("inputs").getJSONObject(index);
        JSONObject witnessUtxo = inputMeta.getJSONObject("witness_utxo");

        String prevTxidHex = vin.getString("txid");          // big-endian display order
        int    prevVout    = vin.getInt("vout");
        long   sequence    = vin.optLong("sequence", 0xFFFFFFFDL);

        long amountSat = witnessUtxo.getBigDecimal("amount")
                .multiply(BigDecimal.valueOf(100_000_000L))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();

        // Find the address_n path belonging to our device's key
        int[] addressN = extractAddressN(inputMeta);

        byte[] prevHashLE = reverseHex(prevTxidHex); // protobuf expects internal LE order

        byte[] inputType = ProtoEncoder.encodeTxInputType(
                addressN,
                prevHashLE,
                prevVout,
                sequence,
                SPENDWITNESS,
                amountSat);

        java.io.ByteArrayOutputStream txType = new java.io.ByteArrayOutputStream();
        writeEmbedded(txType, 2, inputType); // field 2 = repeated inputs

        return wrapTxAck(txType.toByteArray());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TXOUTPUT — one output record, identified by requestIndex
    // ─────────────────────────────────────────────────────────────────────

    private static byte[] buildOutput(JSONObject decoded, int index) {
        JSONObject vout = decoded.getJSONObject("tx")
                .getJSONArray("vout").getJSONObject(index);

        JSONObject outputMeta = decoded.getJSONArray("outputs").getJSONObject(index);

        long amountSat = vout.getBigDecimal("value")
                .multiply(BigDecimal.valueOf(100_000_000L))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();

        String address = vout.getJSONObject("scriptPubKey")
                .optString("address", null);

        // If this output belongs back to our own wallet (change), Trezor
        // re-derives the address from address_n and verifies it matches —
        // safer than trusting the address string for our own change output.
        int[] addressN = extractAddressN(outputMeta);

        boolean isOurChange = addressN.length > 0;

        byte[] outputType = ProtoEncoder.encodeTxOutputType(
                isOurChange ? null : address,
                isOurChange ? addressN : new int[0],
                amountSat,
                PAYTOWITNESS);

        java.io.ByteArrayOutputStream txType = new java.io.ByteArrayOutputStream();
        writeEmbedded(txType, 3, outputType); // field 3 = repeated outputs

        return wrapTxAck(txType.toByteArray());
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads bip32_derivs[0].path from a PSBT input/output's decoded JSON
     * and returns it as Trezor's int[] address_n format. Returns empty
     * array if no bip32_derivs present (e.g. external destination output).
     */
    private static int[] extractAddressN(JSONObject inputOrOutputMeta) {
        JSONArray derivs = inputOrOutputMeta.optJSONArray("bip32_derivs");
        if (derivs == null || derivs.length() == 0) {
            return new int[0];
        }
        String path = derivs.getJSONObject(0).getString("path");
        return BipPathParser.parse(path);
    }

    /** Reverses a big-endian display-order hex txid to internal LE byte order. */
    private static byte[] reverseHex(String hexBigEndian) {
        byte[] b = HexUtils.hexToBytes(hexBigEndian);
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    /** Wraps a TransactionType payload as TxAck field 2. */
    private static byte[] wrapTxAck(byte[] transactionTypeBytes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeEmbedded(out, 2, transactionTypeBytes); // TxAck field 2 = tx
        return out.toByteArray();
    }

    private static void writeVarintField(java.io.ByteArrayOutputStream out, int fieldNumber, long value) {
        writeTag(out, fieldNumber, 0);
        writeRawVarint(out, value);
    }

    private static void writeEmbedded(java.io.ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        writeTag(out, fieldNumber, 2);
        writeRawVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeTag(java.io.ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeRawVarint(java.io.ByteArrayOutputStream out, long value) {
        long v = value;
        while (true) {
            if ((v & ~0x7FL) == 0) { out.write((int) v); return; }
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
    }
}