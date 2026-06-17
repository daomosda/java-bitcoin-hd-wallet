/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */


import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal hand-rolled Protobuf decoder for Trezor response messages:
 *   PublicKey, Address, TxRequest, Failure.
 *
 * Walks the raw field/wire-type/value triples generically, then
 * extracts only the fields each result type cares about.
 */
public final class ProtoDecoder {

    private ProtoDecoder() {}

    // ─────────────────────────────────────────────────────────────────────
    // PublicKey
    //   1: HDNodeType node          (embedded — field 3 = public_key bytes,
    //                                 field 5 = fingerprint uint32 is NOT
    //                                 in HDNodeType; Trezor derives the
    //                                 *parent* fingerprint into node.fingerprint,
    //                                 field 5)
    //   2: string xpub
    // ─────────────────────────────────────────────────────────────────────

    public record PublicKeyResult(String xpub, String fingerprintHex, byte[] pubkeyBytes) {}

    public static PublicKeyResult decodePublicKey(byte[] payload) {
        String xpub = null;
        byte[] nodeBytes = null;

        for (Field f : readFields(payload)) {
            if (f.number == 1 && f.wireType == 2) nodeBytes = f.bytesValue;
            if (f.number == 2 && f.wireType == 2) xpub = new String(f.bytesValue, StandardCharsets.UTF_8);
        }

        String fingerprintHex = "";
        byte[] pubkeyBytes = new byte[0];

        if (nodeBytes != null) {
            for (Field nf : readFields(nodeBytes)) {
                // HDNodeType field 5 = fingerprint (uint32), field 6 = public_key (bytes)
                if (nf.number == 5 && nf.wireType == 0) {
                    fingerprintHex = String.format("%08x", nf.varintValue & 0xFFFFFFFFL);
                }
                if (nf.number == 6 && nf.wireType == 2) {
                    pubkeyBytes = nf.bytesValue;
                }
            }
        }

        return new PublicKeyResult(xpub, fingerprintHex, pubkeyBytes);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Address
    //   1: string address
    // ─────────────────────────────────────────────────────────────────────

    public static String decodeAddress(byte[] payload) {
        for (Field f : readFields(payload)) {
            if (f.number == 1 && f.wireType == 2) {
                return new String(f.bytesValue, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Failure
    //   1: enum FailureType code
    //   2: string message
    // ─────────────────────────────────────────────────────────────────────

    public static String decodeFailureMessage(byte[] payload) {
        for (Field f : readFields(payload)) {
            if (f.number == 2 && f.wireType == 2) {
                return new String(f.bytesValue, StandardCharsets.UTF_8);
            }
        }
        return "Unknown failure (no message field present)";
    }

    // ─────────────────────────────────────────────────────────────────────
    // TxRequest
    //   1: TxRequestDetailsType   details        (field 1 = request_index,
    //                                              field 2 = tx_hash)
    //   2: TxRequestSerializedType serialized    (field 2 = signature_index,
    //                                              field 3 = signature)
    //   3: enum RequestType request_type
    //       (0=TXINPUT, 1=TXOUTPUT, 2=TXMETA, 3=TXFINISHED,
    //        4=TXEXTRADATA, 5=TXORIGINPUT, 6=TXORIGOUTPUT)
    // ─────────────────────────────────────────────────────────────────────

    public record TxRequestResult(
            int     requestType,
            Integer requestIndex,   // which input/output index Trezor wants next
            byte[]  signature,      // present once Trezor returns a signed input
            Integer signatureIndex) {

        public boolean isFinished() {
            return requestType == 3; // TXFINISHED
        }
        public boolean wantsInput() {
            return requestType == 0; // TXINPUT
        }
        public boolean wantsOutput() {
            return requestType == 1; // TXOUTPUT
        }
        public boolean wantsMeta() {
            return requestType == 2; // TXMETA
        }
    }

    public static TxRequestResult decodeTxRequest(byte[] payload) {
        int requestType = -1;
        Integer requestIndex = null;
        byte[] signature = null;
        Integer signatureIndex = null;

        for (Field f : readFields(payload)) {
            if (f.number == 3 && f.wireType == 0) {
                requestType = (int) f.varintValue;
            }
            if (f.number == 1 && f.wireType == 2) {
                // TxRequestDetailsType — field 1 = request_index (uint32)
                for (Field df : readFields(f.bytesValue)) {
                    if (df.number == 1 && df.wireType == 0) {
                        requestIndex = (int) df.varintValue;
                    }
                }
            }
            if (f.number == 2 && f.wireType == 2) {
                // TxRequestSerializedType — field 2 = signature_index,
                // field 3 = signature bytes
                for (Field sf : readFields(f.bytesValue)) {
                    if (sf.number == 2 && sf.wireType == 0) {
                        signatureIndex = (int) sf.varintValue;
                    }
                    if (sf.number == 3 && sf.wireType == 2) {
                        signature = sf.bytesValue;
                    }
                }
            }
        }

        return new TxRequestResult(requestType, requestIndex, signature, signatureIndex);
    }

    // ─────────────────────────────────────────────────────────────────────
    // GENERIC FIELD READER
    // ─────────────────────────────────────────────────────────────────────

    private record Field(int number, int wireType, long varintValue, byte[] bytesValue) {}

    private static List<Field> readFields(byte[] data) {
        List<Field> fields = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        while (in.available() > 0) {
            long tag = readRawVarint(in);
            int fieldNumber = (int) (tag >>> 3);
            int wireType    = (int) (tag & 0x7);

            switch (wireType) {
                case 0 -> { // varint
                    long v = readRawVarint(in);
                    fields.add(new Field(fieldNumber, wireType, v, null));
                }
                case 1 -> { // fixed64 — skip 8 bytes (not used by our messages)
                    skip(in, 8);
                }
                case 2 -> { // length-delimited
                    int len = (int) readRawVarint(in);
                    byte[] buf = new byte[len];
                    int read = in.read(buf, 0, len);
                    if (read != len) {
                        throw new IllegalStateException(
                                "Truncated protobuf field " + fieldNumber
                                        + ": expected " + len + " got " + read);
                    }
                    fields.add(new Field(fieldNumber, wireType, 0, buf));
                }
                case 5 -> { // fixed32 — skip 4 bytes
                    skip(in, 4);
                }
                default -> throw new IllegalStateException(
                        "Unsupported protobuf wire type " + wireType
                                + " for field " + fieldNumber);
            }
        }
        return fields;
    }

    private static void skip(ByteArrayInputStream in, int n) {
        long skipped = in.skip(n);
        if (skipped != n) {
            throw new IllegalStateException("Could not skip " + n + " bytes");
        }
    }

    private static long readRawVarint(ByteArrayInputStream in) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IllegalStateException("Unexpected end of protobuf data");
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }
}