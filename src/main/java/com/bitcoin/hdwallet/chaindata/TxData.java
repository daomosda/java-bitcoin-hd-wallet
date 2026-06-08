package com.bitcoin.hdwallet.chaindata;

/**
 *
 * @author DAOMOSDA
 */

// ── TxData.java ──────────────────────────────────────────────────────

/**
 * All data for one transaction as returned by getblock(verbosity=2).
 *
 * Maps from getblock → tx[i]:
 *
 * {
 *   "txid":     "b39a63d...",
 *   "version":  2,
 *   "locktime": 0,
 *   "size":     155,
 *   "vsize":    128,
 *   "weight":   512,
 *   "hex":      "020000000001...",
 *   "vin":  [...],
 *   "vout": [...]
 * }
 */

import com.bitcoin.hdwallet.crypto.Crypto;
import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.crypto.Base58Check;
import com.bitcoin.hdwallet.crypto.Bech32;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TxData {

    private String txid;
    private int version;
    private long locktime;

    private List<TxInputData> inputs;
    private List<TxOutputData> outputs;

    private boolean coinbase;

    // 🔹 New fields
    private int size;       // bytes
    private int vsize;      // virtual bytes
    private int weight;     // weight units
    private Long feeSat;    // null for coinbase
    private String rawHex;  // full tx hex (optional but useful)

    // ── Constructor ─────────────────────────────────────────────
    public TxData() {
    }    

    public TxData(String txid,
                  int version,
                  long locktime,
                  List<TxInputData> inputs,
                  List<TxOutputData> outputs,
                  boolean coinbase,
                  int size,
                  int vsize,
                  int weight,
                  Long feeSat,
                  String rawHex) {

        this.txid     = Objects.requireNonNull(txid, "txid");
        this.version  = version;
        this.locktime = locktime;

        this.inputs   = inputs  != null ? List.copyOf(inputs)  : List.of();
        this.outputs  = outputs != null ? List.copyOf(outputs) : List.of();

        this.coinbase = coinbase;

        this.size     = size;
        this.vsize    = vsize;
        this.weight   = weight;

        this.feeSat   = coinbase ? null : feeSat;
        this.rawHex   = rawHex != null ? rawHex : "";
    }
    
    // ── Accessors ───────────────────────────────────────────────

    public String txid()     { return txid; }
    public int version()     { return version; }
    public long locktime()   { return locktime; }
    
    public void setTxid(String txid) {
        this.txid = txid;
    }  
            
    public void setVersion(int version)     { 
        this.version = version; 
    }
    
    public void setLocktime(long locktime)   { 
        this.locktime = locktime; 
    }
    
    public List<TxInputData> inputs()  { return inputs; }
    public List<TxOutputData> outputs(){ return outputs; }
    
    public void setInputs(List<TxInputData> inputs)  { 
        this.inputs = inputs; 
    }
    
    public void setOutputs(List<TxOutputData> outputs){ 
        this.outputs = outputs; 
    }

    public boolean isCoinbase() { return coinbase; }

    public int size()   { return size; }
    public int vsize()  { return vsize; }
    public int weight() { return weight; }

    public Long feeSat() { return feeSat; }

    public String rawHex() { return rawHex; }

    // ── Derived helpers ─────────────────────────────────────────

    public int inputCount()  { return inputs.size(); }
    public int outputCount() { return outputs.size(); }

    public long totalOutputValue() {
        return outputs.stream().mapToLong(TxOutputData::valueSat).sum();
    }

    public boolean hasWitness() {
        return inputs.stream().anyMatch(in ->
            in.witness() != null && !in.witness().isEmpty()
        );
    }

    // If vsize missing → derive from weight
    public int effectiveVsize() {
        if (vsize > 0) return vsize;
        if (weight > 0) return (weight + 3) / 4;
        return size;
    }
    
    // ── Object overrides ───────────────────────────────────────

    @Override
    public String toString() {
        return "TxData{" +
                "txid=" + shortTxid() +
                ", inputs=" + inputs.size() +
                ", outputs=" + outputs.size() +
                ", vsize=" + effectiveVsize() +
                ", feeSat=" + feeSat +
                '}';
    }

    public String shortTxid() {
        return txid.length() >= 12 ? txid.substring(0, 12) : txid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TxData other)) return false;
        return txid.equals(other.txid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txid);
    }
    
    // ── TxData.java — add fromHex() static factory ───────────────────────

    /**
     * Parses a raw transaction hex string into a TxData object.
     *
     * Uses Bitcoin Core's decoderawtransaction RPC to deserialize.
     * If you want a pure-Java parser (no RPC), see fromHexPure() below.
     *
     * Raw tx hex format:
     *   version(4) inputs outputs locktime(4)
     *   SegWit: version(4) marker(1) flag(1) inputs outputs witnesses locktime(4)
     */
    public static TxData fromHex(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("fromHex: hex must not be blank");
        }
        byte[] raw = HexUtils.hexToBytes(hex.strip());
        return parseRawBytes(raw, hex);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pure-Java raw transaction parser (no RPC needed)
    // ─────────────────────────────────────────────────────────────────────

   // ── Restructured parseRawBytes() — full method ────────────────────────

    private static TxData parseRawBytes(byte[] raw, String hex) {
        ByteReader r = new ByteReader(raw);

        // ── version ───────────────────────────────────────────────────────
        int version = r.readInt32LE();

        // ── segwit marker + flag ──────────────────────────────────────────
        boolean segwit = false;
        if (r.peek() == 0x00) {
            r.skip(1);
            byte flag = r.readByte();
            if (flag != 0x01) {
                throw new IllegalArgumentException(
                    "fromHex: unknown segwit flag: " + flag);
            }
            segwit = true;
        }

        // ── inputs ────────────────────────────────────────────────────────
        int inputCount = (int) r.readVarInt();
        List<TxInputData> inputs = new ArrayList<>(inputCount);

        for (int i = 0; i < inputCount; i++) {
            byte[] prevTxidBytes = r.readBytes(32);
            String prevTxid      = bytesToHexReversed(prevTxidBytes);
            int    prevVout      = r.readInt32LE();
            byte[] scriptSig     = r.readVarBytes();
            long   sequence      = r.readUint32LE();

            boolean isCoinbaseTx =
                prevTxid.equals("0000000000000000000000000000000000000000"
                               + "000000000000000000000000")
                && (prevVout & 0xFFFFFFFFL) == 0xFFFFFFFFL;

            inputs.add(new TxInputData(
                isCoinbaseTx ? null : prevTxid,
                isCoinbaseTx ? null : prevVout,
                isCoinbaseTx ? HexUtils.bytesToHex(scriptSig) : null,
                HexUtils.bytesToHex(scriptSig),
                sequence,
                List.of()                        // witness filled in below                
            ));
        }

        // ── outputs — parse raw data first, txid added after ─────────────
        int outputCount = (int) r.readVarInt();

        // temporary holders before we know the txid
        long[]   rawValues  = new long  [outputCount];
        byte[][] rawScripts = new byte  [outputCount][];

        for (int i = 0; i < outputCount; i++) {
            rawValues [i] = r.readInt64LE();
            rawScripts[i] = r.readVarBytes();
        }

        // ── witness ───────────────────────────────────────────────────────
        if (segwit) {
            for (int i = 0; i < inputCount; i++) {
                long         stackItems = r.readVarInt();
                List<String> witness    = new ArrayList<>();
                for (long w = 0; w < stackItems; w++) {
                    witness.add(HexUtils.bytesToHex(r.readVarBytes()));
                }
                TxInputData old = inputs.get(i);
                inputs.set(i, new TxInputData(
                    old.prevTxId(),
                    old.prevVout(),
                    old.coinbaseData(),
                    old.scriptSigHex(),
                    old.sequence(),
                    List.copyOf(witness)                    
                ));
            }
        }

        // ── locktime ──────────────────────────────────────────────────────
        long locktime = r.readUint32LE();

        // ── txid (SHA256d of non-witness bytes, reversed) ─────────────────
        String txid = computeTxid(raw, segwit);

        // ── build TxOutputData now that txid is known ─────────────────────
        List<TxOutputData> outputs = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) {
            byte[] scriptPub = rawScripts[i];
            outputs.add(new TxOutputData(
                txid,                           // ← txid now available
                i,                              // vout index
                rawValues[i],                   // valueSat
                HexUtils.bytesToHex(scriptPub),          // scriptHex
                classifyScript(scriptPub),      // scriptType
                extractAddress(scriptPub)       // address (null for OP_RETURN)
            ));
        }

        // ── sizes ─────────────────────────────────────────────────────────
        int size   = raw.length;
        int weight = segwit ? computeWeight(raw) : size * 4;
        int vsize  = (weight + 3) / 4;

        boolean coinbase = !inputs.isEmpty() && inputs.get(0).isCoinbase();

        return new TxData(
            txid,
            version,
            locktime,
            inputs,
            outputs,
            coinbase,
            size,
            vsize,
            weight,
            null,   // fee unknown without UTXO set
            hex
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // ByteReader — sequential raw byte parser
    // ─────────────────────────────────────────────────────────────────────

    private static final class ByteReader {

        private final byte[] data;
        private int pos = 0;

        ByteReader(byte[] data) { this.data = data; }

        byte readByte() {
            return data[pos++];
        }

        byte peek() {
            return data[pos];
        }

        void skip(int n) {
            pos += n;
        }

        byte[] readBytes(int n) {
            byte[] out = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return out;
        }

        /** 4-byte signed little-endian int (version, vout). */
        int readInt32LE() {
            int v = (data[pos] & 0xFF)
                  | ((data[pos+1] & 0xFF) <<  8)
                  | ((data[pos+2] & 0xFF) << 16)
                  | ((data[pos+3] & 0xFF) << 24);
            pos += 4;
            return v;
        }

        /** 4-byte unsigned little-endian (sequence, locktime). */
        long readUint32LE() {
            long v = (data[pos] & 0xFFL)
                   | ((data[pos+1] & 0xFFL) <<  8)
                   | ((data[pos+2] & 0xFFL) << 16)
                   | ((data[pos+3] & 0xFFL) << 24);
            pos += 4;
            return v;
        }

        /** 8-byte signed little-endian (output value in satoshis). */
        long readInt64LE() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v |= (data[pos + i] & 0xFFL) << (8 * i);
            }
            pos += 8;
            return v;
        }

        /**
         * Bitcoin variable-length integer.
         * < 0xFD         → 1 byte
         * == 0xFD        → next 2 bytes (little-endian)
         * == 0xFE        → next 4 bytes (little-endian)
         * == 0xFF        → next 8 bytes (little-endian)
         */
        long readVarInt() {
            int first = data[pos++] & 0xFF;
            if (first < 0xFD) return first;
            if (first == 0xFD) {
                long v = (data[pos] & 0xFFL) | ((data[pos+1] & 0xFFL) << 8);
                pos += 2;
                return v;
            }
            if (first == 0xFE) {
                long v = readUint32LE();
                return v;
            }
            // 0xFF — 8 byte
            long v = readInt64LE();
            return v;
        }

        /** Reads a varint-prefixed byte array. */
        byte[] readVarBytes() {
            int len = (int) readVarInt();
            return readBytes(len);
        }

        int remaining() { return data.length - pos; }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Txid computation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes the txid from raw bytes.
     *
     * Legacy: txid = SHA256d(raw), reversed
     * SegWit: txid = SHA256d(non-witness serialisation), reversed
     *         (witness data is excluded from txid — that is wtxid)
     */
    private static String computeTxid(byte[] raw, boolean segwit) {
        byte[] toHash;

        if (segwit) {
            // strip marker + flag + witness from serialisation
            toHash = stripWitness(raw);
        } else {
            toHash = raw;
        }

        byte[] hash = Crypto.sha256(toHash);
        // reverse for display (Bitcoin convention)
        reverseInPlace(hash);
        return HexUtils.bytesToHex(hash);
    }

    /**
     * Removes the segwit marker, flag, and witness fields from the raw bytes
     * to produce the legacy serialisation used for txid.
     *
     * Input:  [ver:4][0x00][0x01][inputs][outputs][witnesses][locktime:4]
     * Output: [ver:4][inputs][outputs][locktime:4]
     */
    private static byte[] stripWitness(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ByteReader r = new ByteReader(raw);

        // version
        byte[] ver = r.readBytes(4);
        out.write(ver, 0, 4);

        // skip marker + flag
        r.skip(2);

        // inputs
        long inputCount = r.readVarInt();
        writeVarInt(out, inputCount);
        for (long i = 0; i < inputCount; i++) {
            out.write(r.readBytes(32), 0, 32);       // prevTxid
            byte[] vout = intToLE(r.readInt32LE());
            out.write(vout, 0, 4);
            byte[] script = r.readVarBytes();
            writeVarInt(out, script.length);
            out.write(script, 0, script.length);
            byte[] seq = longToLE4(r.readUint32LE());
            out.write(seq, 0, 4);
        }

        // outputs
        long outputCount = r.readVarInt();
        writeVarInt(out, outputCount);
        for (long i = 0; i < outputCount; i++) {
            byte[] value = longToLE8(r.readInt64LE());
            out.write(value, 0, 8);
            byte[] script = r.readVarBytes();
            writeVarInt(out, script.length);
            out.write(script, 0, script.length);
        }

        // skip witness stacks
        for (long i = 0; i < inputCount; i++) {
            long stackItems = r.readVarInt();
            for (long w = 0; w < stackItems; w++) {
                r.readVarBytes();   // discard
            }
        }

        // locktime
        out.write(r.readBytes(4), 0, 4);

        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Weight computation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * BIP141 weight:
     *   weight = base_size * 4 + witness_size
     *
     *   base_size   = version + inputs + outputs + locktime (no marker/flag/witness)
     *   witness_size= marker(1) + flag(1) + witness stacks
     */
    private static int computeWeight(byte[] raw) {
        byte[] base = stripWitness(raw);
        int    baseSize    = base.length;
        int    witnessSize = raw.length - baseSize + 2;  // +2 for marker+flag
        return baseSize * 4 + witnessSize;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Script classification + address extraction
    // ─────────────────────────────────────────────────────────────────────

    private static String classifyScript(byte[] script) {
        int len = script.length;
        if (len == 0) return "empty";

        // P2PKH: OP_DUP OP_HASH160 <20> <hash> OP_EQUALVERIFY OP_CHECKSIG
        if (len == 25
                && script[0]  == 0x76
                && script[1]  == (byte)0xa9
                && script[2]  == 0x14
                && script[23] == (byte)0x88
                && script[24] == (byte)0xac) {
            return "pubkeyhash";
        }

        // P2SH: OP_HASH160 <20> <hash> OP_EQUAL
        if (len == 23
                && script[0]  == (byte)0xa9
                && script[1]  == 0x14
                && script[22] == (byte)0x87) {
            return "scripthash";
        }

        // P2WPKH: OP_0 <20>
        if (len == 22 && script[0] == 0x00 && script[1] == 0x14) {
            return "witness_v0_keyhash";
        }

        // P2WSH: OP_0 <32>
        if (len == 34 && script[0] == 0x00 && script[1] == 0x20) {
            return "witness_v0_scripthash";
        }

        // P2TR (Taproot): OP_1 <32>
        if (len == 34 && script[0] == 0x51 && script[1] == 0x20) {
            return "witness_v1_taproot";
        }

        // OP_RETURN
        if (script[0] == 0x6a) {
            return "nulldata";
        }

        // P2PK: <pubkey> OP_CHECKSIG
        if ((len == 35 || len == 67)
                && script[len-1] == (byte)0xac) {
            return "pubkey";
        }

        return "nonstandard";
    }

    private static String extractAddress(byte[] script) {
        String type = classifyScript(script);
        try {
            return switch (type) {
                case "pubkeyhash" -> {
                    byte[] hash = Arrays.copyOfRange(script, 3, 23);
                    yield Base58Check.base58CheckEncode((byte) 0x6f, hash); // testnet P2PKH
                }
                case "scripthash" -> {
                    byte[] hash = Arrays.copyOfRange(script, 2, 22);
                    yield Base58Check.base58CheckEncode((byte) 0xc4, hash); // testnet P2SH
                }
                case "witness_v0_keyhash" -> {
                    byte[] prog = Arrays.copyOfRange(script, 2, 22);
                    yield Bech32.encodeSegWitAddress("tb", 0, prog); // testnet
                }
                case "witness_v0_scripthash" -> {
                    byte[] prog = Arrays.copyOfRange(script, 2, 34);
                    yield Bech32.encodeSegWitAddress("tb", 0, prog);
                }
                case "witness_v1_taproot" -> {
                    byte[] prog = Arrays.copyOfRange(script, 2, 34);
                    yield Bech32.encodeSegWitAddress("tb", 1, prog);
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Byte utilities
    // ─────────────────────────────────────────────────────────────────────

    /*
    private static byte[] hexToBytes(String hex) {
        int    len  = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte)((Character.digit(hex.charAt(i),   16) << 4)
                               +   Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
    */
    
    /** Reverses bytes and returns hex — used for txid display. */
    private static String bytesToHexReversed(byte[] bytes) {
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        reverseInPlace(copy);
        return HexUtils.bytesToHex(copy);
    }

    private static void reverseInPlace(byte[] arr) {
        for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
            byte t = arr[i]; arr[i] = arr[j]; arr[j] = t;
        }
    }

    /*
    private static byte[] sha256d(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
    */

    private static byte[] intToLE(int v) {
        return new byte[]{
            (byte)(v),       (byte)(v >> 8),
            (byte)(v >> 16), (byte)(v >> 24)
        };
    }

    private static byte[] longToLE4(long v) {
        return new byte[]{
            (byte)(v),       (byte)(v >> 8),
            (byte)(v >> 16), (byte)(v >> 24)
        };
    }

    private static byte[] longToLE8(long v) {
        return new byte[]{
            (byte)(v),       (byte)(v >> 8),
            (byte)(v >> 16), (byte)(v >> 24),
            (byte)(v >> 32), (byte)(v >> 40),
            (byte)(v >> 48), (byte)(v >> 56)
        };
    }

    private static void writeVarInt(OutputStream out, long v) {
        try {
            if (v < 0xFD) {
                out.write((int) v);
            } else if (v <= 0xFFFF) {
                out.write(0xFD);
                out.write((int)(v & 0xFF));
                out.write((int)((v >> 8) & 0xFF));
            } else if (v <= 0xFFFFFFFFL) {
                out.write(0xFE);
                out.write((int)(v & 0xFF));
                out.write((int)((v >> 8)  & 0xFF));
                out.write((int)((v >> 16) & 0xFF));
                out.write((int)((v >> 24) & 0xFF));
            } else {
                out.write(0xFF);
                for (int i = 0; i < 8; i++) out.write((int)((v >> (8*i)) & 0xFF));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    
    // -------------------------------------------------
    // BUILD FROM decodepsbt RESULT
    // -------------------------------------------------

    public static TxData fromDecodedPsbt(
        JSONObject decodedPsbt
    ) {

        /*
         * Expected decodepsbt structure:
         *
         * {
         *   "tx": {
         *      "txid": "...",
         *      "hash": "...",
         *      "version": 2,
         *      "size": ...,
         *      "vsize": ...,
         *      "weight": ...,
         *      "locktime": 0,
         *      "vin": [...],
         *      "vout": [...]
         *   },
         *   "inputs": [...],
         *   "outputs": [...]
         * }
         */

        if (decodedPsbt == null) {
            throw new IllegalArgumentException(
                    "decodedPsbt is null"
            );
        }

        if (!decodedPsbt.has("tx")) {
            throw new IllegalArgumentException(
                    "decodepsbt result does not contain 'tx'"
            );
        }

        JSONObject txObj =
                decodedPsbt.getJSONObject("tx");

        TxData tx = new TxData();

        // -------------------------------------------------
        // BASIC TX FIELDS
        // -------------------------------------------------

        tx.setTxid(
                txObj.optString("txid", "")
        );

        tx.setVersion(
                txObj.optInt("version", 2)
        );

        tx.setLocktime(
                txObj.optLong("locktime", 0L)
        );

        // -------------------------------------------------
        // INPUTS
        // -------------------------------------------------

        JSONArray vin =
                txObj.optJSONArray("vin");
        List<TxInputData> inputList = new ArrayList<>();

        if (vin != null) {

            for (int i = 0; i < vin.length(); i++) {

                JSONObject in =
                        vin.getJSONObject(i);

                TxInputData txIn =
                        new TxInputData();

                /*
                 * Coinbase txs do not have txid/vout
                 */
                if (in.has("coinbase")) {

                    //txIn.setCoinbase(true);

                    txIn.setCoinbaseData(
                            in.getString("coinbase")
                    );

                } else {

                    //txIn.setCoinbase(false);

                    txIn.setPrevTxId(
                            in.getString("txid")
                    );

                    txIn.setPrevVout(
                            in.getInt("vout")
                    );
                }

                txIn.setSequence(
                        in.optLong(
                                "sequence",
                                0xffffffffL
                        )
                );

                /*
                 * Optional witness
                 */
                JSONArray witness =
                        in.optJSONArray("txinwitness");

                if (witness != null) {

                    List<String> witnessStack =
                            new ArrayList<>();

                    for (int w = 0; w < witness.length(); w++) {
                        witnessStack.add(
                                witness.getString(w)
                        );
                    }

                    txIn.setWitness(witnessStack);
                }

                //tx.getInputs().add(txIn);
                inputList.add(txIn);
            }
            tx.setInputs(inputList);
        }

        // -------------------------------------------------
        // OUTPUTS
        // -------------------------------------------------

        JSONArray vout =
                txObj.optJSONArray("vout");
        
        List<TxOutputData> outputList = new ArrayList<>();

        if (vout != null) {

            for (int i = 0; i < vout.length(); i++) {

                JSONObject out =
                        vout.getJSONObject(i);

                TxOutputData txOut =
                        new TxOutputData();

                /*
                 * BTC -> satoshis
                 */
                double btc =
                        out.getDouble("value");

                long sat =
                        btcToSat(btc);

                txOut.setValueSat(sat);

                txOut.setVout(
                        out.optInt("n", i)
                );

                JSONObject scriptPubKey =
                        out.getJSONObject("scriptPubKey");

                String scriptHex =
                        scriptPubKey.getString("hex");

                txOut.setScriptHex(scriptHex);

                /*
                 * Optional address
                 */
                if (scriptPubKey.has("address")) {

                    txOut.setAddress(
                            scriptPubKey.getString("address")
                    );
                }

                /*
                 * Optional type
                 */
                txOut.setScriptType(
                        scriptPubKey.optString(
                                "type",
                                "unknown"
                        )
                );

                //tx.getOutputs().add(txOut);
                outputList.add(txOut);
            }
            tx.setOutputs(outputList);
        }

        return tx;
    }

    // -------------------------------------------------
    // HELPERS
    // -------------------------------------------------

    private static long btcToSat(double btc) {

        return Math.round(
                btc * 100_000_000L
        );
    }

    // -------------------------------------------------
    // GETTERS
    // -------------------------------------------------

    public int getVersion() {
        return version;
    }

    public long getLocktime() {
        return locktime;
    }

    public List<TxInputData> getInputs() {
        return inputs;
    }

    public List<TxOutputData> getOutputs() {
        return outputs;
    }
}
