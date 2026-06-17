/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Minimal hand-rolled Protobuf encoder for the specific Trezor
 * message types this integration uses:
 *
 *   GetPublicKey { repeated uint32 address_n; bool show_display;
 *                  string coin_name; }
 *   GetAddress   { repeated uint32 address_n; string coin_name;
 *                  bool show_display; uint32 script_type; }
 *   SignTx       { uint32 outputs_count; uint32 inputs_count;
 *                  string coin_name; }
 *
 * Field numbers below match Trezor's public messages.proto
 * (trezor-common repository) for these three message types.
 *
 * Wire types used:
 *   0 = varint   (uint32, bool, enum)
 *   2 = length-delimited (string, bytes, packed repeated)
 */
public final class ProtoEncoder {

    private ProtoEncoder() {}

    // ─────────────────────────────────────────────────────────────────────
    // GetPublicKey  (field numbers per messages.proto)
    //   1: repeated uint32 address_n  [packed]
    //   2: bool show_display
    //   3: string coin_name
    // ─────────────────────────────────────────────────────────────────────

    public static byte[] encodeGetPublicKey(
            int[]   addressN,
            boolean showDisplay,
            String  coinName) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writePackedUint32Field(out, 1, addressN);
        writeBoolField(out, 2, showDisplay);
        writeStringField(out, 3, coinName);

        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GetAddress
    //   1: repeated uint32 address_n  [packed]
    //   2: string coin_name
    //   3: bool show_display
    //   4: uint32 script_type  (0=SPENDADDRESS,1=SPENDMULTISIG,
    //                            2=EXTERNAL,3=SPENDWITNESS,4=SPENDP2SHWITNESS)
    // ─────────────────────────────────────────────────────────────────────

    public static byte[] encodeGetAddress(
            int[]   addressN,
            String  coinName,
            boolean showDisplay,
            int     scriptType) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writePackedUint32Field(out, 1, addressN);
        writeStringField(out, 2, coinName);
        writeBoolField(out, 3, showDisplay);
        writeVarintField(out, 4, scriptType);

        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SignTx
    //   1: uint32 outputs_count
    //   2: uint32 inputs_count
    //   3: string coin_name
    // ─────────────────────────────────────────────────────────────────────

    public static byte[] encodeSignTx(
            int    inputsCount,
            int    outputsCount,
            String coinName) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeVarintField(out, 1, outputsCount);
        writeVarintField(out, 2, inputsCount);
        writeStringField(out, 3, coinName);

        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TxInputType  — embedded message used by TxAckBuilder
    //   1: repeated uint32 address_n [packed]
    //   2: bytes  prev_hash      (32 bytes, internal little-endian order)
    //   3: uint32 prev_index
    //   5: uint32 sequence
    //   6: enum   script_type    (0=SPENDADDRESS,3=SPENDWITNESS,...)
    //   8: uint64 amount         (satoshis, required for SPENDWITNESS)
    // ─────────────────────────────────────────────────────────────────────

    public static byte[] encodeTxInputType(
            int[]  addressN,
            byte[] prevHashLE,
            long   prevIndex,
            long   sequence,
            int    scriptType,
            long   amountSat) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writePackedUint32Field(out, 1, addressN);
        writeBytesField(out, 2, prevHashLE);
        writeVarintField(out, 3, prevIndex);
        writeVarintField(out, 5, sequence);
        writeVarintField(out, 6, scriptType);
        writeVarintField(out, 8, amountSat);

        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TxOutputType — embedded message used by TxAckBuilder
    //   1: string address          (only one of address/address_n used)
    //   2: repeated uint32 address_n [packed]
    //   3: uint64 amount
    //   4: enum   script_type  (0=PAYTOADDRESS,2=PAYTOWITNESS,...)
    // ─────────────────────────────────────────────────────────────────────

    public static byte[] encodeTxOutputType(
            String address,
            int[]  addressN,
            long   amountSat,
            int    scriptType) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (address != null && !address.isBlank()) {
            writeStringField(out, 1, address);
        }
        if (addressN != null && addressN.length > 0) {
            writePackedUint32Field(out, 2, addressN);
        }
        writeVarintField(out, 3, amountSat);
        writeVarintField(out, 4, scriptType);

        return out.toByteArray();
    }

    /**
     * Wraps an embedded message (TxInputType/TxOutputType) as field N
     * of the parent TxAck.TransactionType message.
     */
    public static byte[] wrapAsField(int fieldNumber, byte[] embeddedBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytesField(out, fieldNumber, embeddedBytes);
        return out.toByteArray();
    }

    public static byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] c : chunks) out.write(c, 0, c.length);
        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOW-LEVEL WIRE FORMAT HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** tag = (field_number << 3) | wire_type */
    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, long value) {
        writeTag(out, fieldNumber, 0); // wire type 0 = varint
        writeRawVarint(out, value);
    }

    private static void writeBoolField(ByteArrayOutputStream out, int fieldNumber, boolean value) {
        writeVarintField(out, fieldNumber, value ? 1 : 0);
    }

    private static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeBytesField(out, fieldNumber, bytes);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        writeTag(out, fieldNumber, 2); // wire type 2 = length-delimited
        writeRawVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    /**
     * Packed repeated uint32 — single length-delimited field containing
     * concatenated varints (protobuf "packed" encoding, used for
     * repeated scalar fields like address_n).
     */
    private static void writePackedUint32Field(ByteArrayOutputStream out, int fieldNumber, int[] values) {
        if (values == null || values.length == 0) return;

        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (int v : values) {
            // address_n components are uint32 — mask to unsigned 32-bit
            // range before varint-encoding (handles hardened bit correctly)
            writeRawVarint(packed, v & 0xFFFFFFFFL);
        }

        writeTag(out, fieldNumber, 2);
        byte[] packedBytes = packed.toByteArray();
        writeRawVarint(out, packedBytes.length);
        out.write(packedBytes, 0, packedBytes.length);
    }

    /** Standard protobuf base-128 varint, little-endian group order. */
    private static void writeRawVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while (true) {
            if ((v & ~0x7FL) == 0) {
                out.write((int) v);
                return;
            } else {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
        }
    }
}