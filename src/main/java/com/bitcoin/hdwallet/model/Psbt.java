package com.bitcoin.hdwallet.model;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.tranxcontrol.PsbtUtils;
import java.io.*;
import java.util.*;

/**
 * Minimal PSBT (BIP174) parser and writer.
 *
 * PSBT binary format:
 *
 *   magic:    0x70736274 ("psbt") + 0xFF separator
 *   global:   key-value pairs terminated by 0x00
 *     key 0x00 = unsigned transaction (raw tx bytes)
 *     key 0x01 = xpub
 *     key 0xFB = version
 *   per-input maps (one per tx input), each terminated by 0x00
 *     key 0x02 = partial_sig  (pubkey → DER+hashtype)
 *     key 0x03 = sighash_type
 *     key 0x04 = redeem_script
 *     key 0x05 = witness_script
 *     key 0x06 = bip32_deriv
 *     key 0x08 = witness_utxo
 *     key 0x09 = non_witness_utxo
 *   per-output maps (one per tx output), each terminated by 0x00
 *     key 0x02 = bip32_deriv
 */
public final class Psbt {

    // ── Magic ─────────────────────────────────────────────────────────
    private static final byte[] MAGIC = {0x70, 0x73, 0x62, 0x74, (byte)0xFF};

    // ── PSBT global key types ─────────────────────────────────────────
    private static final int PSBT_GLOBAL_UNSIGNED_TX = 0x00;

    // ── PSBT per-input key types ──────────────────────────────────────
    private static final int PSBT_IN_PARTIAL_SIG      = 0x02;
    // ── Parsed state ──────────────────────────────────────────────────

    /** Raw bytes of the unsigned transaction (global key 0x00). */
    private static byte[] unsignedTxBytes;  
    private final Map<ByteKey, byte[]> globalMap;
    private final List<PsbtInput> inputs;
    private final List<Map<ByteKey, byte[]>> outputMaps;
    private final List<KV> globalExtras;

    /** Number of inputs  (derived from unsigned tx). */
    private int inputCount;

    /** Number of outputs (derived from unsigned tx). */
    private int outputCount;

    /**
     * Per-input maps: inputMaps[i] = map of (keyBytes → valueBytes).
     * The key bytes include the key type byte as the first byte.
     */
    private List<Map<ByteKey, byte[]>> inputMaps;

    /**
     * Per-output maps: outputMaps[i] = map of (keyBytes → valueBytes).
     */
    
    // ── Raw bytes for re-serialization ───────────────────────────────
    private byte[] originalBytes;
    
    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────
    
     private static final byte[] PSBT_MAGIC = new byte[] {
        (byte) 0x70, (byte) 0x73, (byte) 0x62, (byte) 0x74, (byte) 0xff
    };
   
    private Map<PsbtKey, byte[]> globalEntries;
    
    // No-arg constructor for parse() + doParse()
    public Psbt() {
        this.unsignedTxBytes = null;
        this.globalMap       = new LinkedHashMap<>();
        this.inputs          = new ArrayList<>();
        this.outputMaps      = new ArrayList<>();
        this.globalExtras    = new ArrayList<>();
    }  
    
    public Psbt(byte[] unsignedTxBytes,
            Map<ByteKey, byte[]> globalMap,
            List<PsbtInput> inputs,
            List<Map<ByteKey, byte[]>> outputMaps) {
        this.unsignedTxBytes = unsignedTxBytes;
        this.globalMap       = globalMap  != null ? globalMap  : new LinkedHashMap<>();
        this.inputs          = inputs     != null ? inputs     : new ArrayList<>();
        this.outputMaps      = outputMaps != null ? outputMaps : new ArrayList<>();
        this.globalExtras    = new ArrayList<>();
    }    

    // Convenience constructor used by parseBase64
    public Psbt(byte[] unsignedTxBytes,
                List<PsbtInput> inputs,
                List<Map<ByteKey, byte[]>> outputMaps) {
        this(unsignedTxBytes, new LinkedHashMap<>(), inputs, outputMaps);
    }
    
    public List<PsbtInput> getInputs() {
        return java.util.Collections.unmodifiableList(inputs);
    }

    public Map<ByteKey, byte[]> getGlobalMap() {
        return globalMap;
    }

    public List<Map<ByteKey, byte[]>> getOutputMaps() {
        return java.util.Collections.unmodifiableList(outputMaps);
    }
    
    public void addSignatureToInput(int inputIndex, byte[] pubkey, byte[] sig) {
        PsbtInput in = inputs.get(inputIndex);
        in.partialSigs.put(new ByteArrayWrapper(pubkey), sig);
    }

    /**
     * Parses a base64-encoded PSBT string.
     * @param base64
     * @return 
     * @throws java.io.IOException
     */
    public static Psbt fromBase64(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        return parse(bytes);
    }

    /**
     * Parses raw PSBT bytes.
     * @param bytes
     * @return 
     * @throws java.io.IOException
     */
    public static Psbt parse(byte[] bytes) throws IOException {
        Psbt psbt = new Psbt();
        psbt.originalBytes = bytes;
        psbt.doParse(bytes);
        System.out.println("parsed inputs = " + psbt.getInputs().size());
        System.out.println("parsed outputs = " + psbt.getOutputs().size());
        return psbt;
    }

    /**
     * Adds a partial signature to input[inputIndex].
     *
     * @param inputIndex  which input to sign
     * @param pubKey      33-byte compressed public key
     * @param derSigWithHashType  DER signature + sighash type byte
     */
    public void addPartialSig(int    inputIndex,
                               byte[] pubKey,
                               byte[] derSigWithHashType) {
        // PSBT partial_sig key = [0x02] + pubkey (34 bytes total)
        byte[] keyBytes = new byte[1 + pubKey.length];
        keyBytes[0] = PSBT_IN_PARTIAL_SIG;
        System.arraycopy(pubKey, 0, keyBytes, 1, pubKey.length);

        inputMaps.get(inputIndex).put(new ByteKey(keyBytes), derSigWithHashType);
    }
    
    public static Psbt parseBase64(String b64) {
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(b64);
            ByteArrayInputStream in = new ByteArrayInputStream(raw);

            // 1. Check magic
            byte[] magic = new byte[PSBT_MAGIC.length];
            if (in.read(magic) != magic.length) {
                throw new IllegalArgumentException("PSBT too short");
            }
            if (!java.util.Arrays.equals(magic, PSBT_MAGIC)) {
                throw new IllegalArgumentException("Invalid PSBT magic header");
            }

            Map<ByteKey, byte[]> globalMap = readByteKeyMap(in);

            for (Map.Entry<ByteKey, byte[]> e : globalMap.entrySet()) {
                byte[] keyBytes = e.getKey().bytes; // or equivalent
                if (keyBytes != null && keyBytes.length > 0 && (keyBytes[0] & 0xFF) == 0x00) {
                    // type 0x00 = PSBT_GLOBAL_UNSIGNED_TX
                    unsignedTxBytes = e.getValue();
                    break;
                }
            }

            if (unsignedTxBytes == null) {
                throw new IllegalArgumentException("PSBT missing global unsigned tx (type=0x00)");
            }

            TxCounts counts = parseTxCounts(unsignedTxBytes);

            List<PsbtInput> inputs = new ArrayList<>();
            for (int i = 0; i < counts.numInputs; i++) {
                Map<ByteKey, byte[]> map = readByteKeyMap(in);
                inputs.add(new PsbtInput(map));
            }

            // outputs can stay as raw maps for now
            List<Map<ByteKey, byte[]>> outputMaps = new ArrayList<>();
            for (int i = 0; i < counts.numOutputs; i++) {
                outputMaps.add(readByteKeyMap(in));
            }

            return new Psbt(unsignedTxBytes, globalMap, inputs, outputMaps);
        } catch (IOException e) {
            throw new RuntimeException("PSBT parse error", e);
        }
    }

    private static Map<ByteKey, byte[]> readByteKeyMap(ByteArrayInputStream in) throws IOException {
        Map<ByteKey, byte[]> map = new LinkedHashMap<>();
        while (true) {
            long keyLen = readCompactSize(in);
            if (keyLen == 0) break;

            int keyType = in.read();
            int keyDataLen = (int) keyLen - 1;
            byte[] keyData = new byte[keyDataLen];
            in.read(keyData);

            long valueLen = readCompactSize(in);
            byte[] value = new byte[(int) valueLen];
            in.read(value);

            byte[] fullKey = new byte[1 + keyDataLen];
            fullKey[0] = (byte) keyType;
            System.arraycopy(keyData, 0, fullKey, 1, keyDataLen);

            map.put(new ByteKey(fullKey), value);
        }
        return map;
    }

    public Map<PsbtKey, byte[]> getGlobalEntries() {
        return globalEntries;
    }

    // Helper: read compactSize from stream (mirror of writeCompactSize)
    private static long readCompactSize(ByteArrayInputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            throw new IllegalArgumentException("Unexpected EOF reading compactSize");
        }

        int ch = first & 0xff;
        if (ch < 0xFD) {
            return ch;
        } else if (ch == 0xFD) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 == -1 || b1 == -1) {
                throw new IllegalArgumentException("EOF in compactSize(0xFD)");
            }
            return ((b1 & 0xff) << 8) | (b0 & 0xff);
        } else if (ch == 0xFE) {
            long res = 0;
            for (int i = 0; i < 4; i++) {
                int b = in.read();
                if (b == -1) {
                    throw new IllegalArgumentException("EOF in compactSize(0xFE)");
                }
                res |= ((long) (b & 0xff)) << (8 * i);
            }
            return res;
        } else {
            long res = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b == -1) {
                    throw new IllegalArgumentException("EOF in compactSize(0xFF)");
                }
                res |= ((long) (b & 0xff)) << (8 * i);
            }
            return res;
        }
    }

    // Minimal tx parser: just enough to get vin/vout counts of the unsigned tx
    private static final class TxCounts {
        final int numInputs;
        final int numOutputs;

        TxCounts(int numInputs, int numOutputs) {
            this.numInputs = numInputs;
            this.numOutputs = numOutputs;
        }
    }

    private static TxCounts parseTxCounts(byte[] tx) throws IOException {
        if (tx == null) {
            throw new IllegalArgumentException("parseTxCounts: tx bytes are null");
        }
        
        ByteArrayInputStream in = new ByteArrayInputStream(tx);

        // version (4 bytes)
        skipBytes(in, 4);

        // vin count (compactSize)
        long vinCount = readCompactSize(in);

        // skip each input
        for (int i = 0; i < vinCount; i++) {
            skipBytes(in, 32);         // prev txid
            skipBytes(in, 4);          // vout
            long scriptLen = readCompactSize(in);
            skipBytes(in, (int) scriptLen); // scriptSig
            skipBytes(in, 4);          // sequence
        }

        // vout count (compactSize)
        long voutCount = readCompactSize(in);

        // skip each output
        for (int i = 0; i < voutCount; i++) {
            skipBytes(in, 8); // amount
            long pkLen = readCompactSize(in);
            skipBytes(in, (int) pkLen);
        }

        // locktime (4 bytes) exists but we don't need it
        return new TxCounts((int) vinCount, (int) voutCount);
    }

    private static void skipBytes(ByteArrayInputStream in, int len) throws IOException {
        long skipped = in.skip(len);
        if (skipped != len) {
            throw new IllegalArgumentException("Unexpected EOF skipping " + len + " bytes");
        }
    }

    /**
     * Returns the raw unsigned transaction bytes (for sighash computation).
     * @return 
     */
    public byte[] getUnsignedTxBytes() {
        return unsignedTxBytes;
    }

    /**
     * Parses and returns the unsigned transaction structure.
     * @return 
     * @throws java.io.IOException
     */
    public ParsedTx getUnsignedTx() throws IOException {
        return ParsedTx.parse(unsignedTxBytes);
    }

    public int getInputCount()  { return inputCount;  }
    public int getOutputCount() { return outputCount; }
    
    public List<Map<ByteKey, byte[]>> getOutputs() {
        return Collections.unmodifiableList(outputMaps);
    }

    /**
     * Returns all key-value pairs for input[i] as read-only map.
     * @param i
     * @return 
     */
    public Map<ByteKey, byte[]> getInputMap(int i) {
        return Collections.unmodifiableMap(inputMaps.get(i));
    }

    /**
     * Serializes the (possibly modified) PSBT back to bytes.
     * @return 
     * @throws java.io.IOException
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        // magic: "psbt" + 0xff
        bos.write(MAGIC);

        // ----- Global map -----

        // 1) Global: unsigned tx (type=0x00, no keydata)
        writeKeyValue(bos,
            new byte[]{ PSBT_GLOBAL_UNSIGNED_TX },   // full key = [0x00]
            unsignedTxBytes
        );

        // 2) Global extras (xpubs, version, proprietary, etc.)
        if (globalExtras != null) {
            for (KV kv : globalExtras) {
                if (kv == null || kv.key == null || kv.value == null) {
                    continue;
                }
                // kv.key must already be full PSBT key: [type][keydata...]
                writeKeyValue(bos, kv.key, kv.value);
            }
        }

        // 3) Global separator
        bos.write(0x00);

        // ----- Per-input maps -----
        if (inputs != null) {
            for (PsbtInput input : inputs) {
                if (input == null) {
                    bos.write(0x00);
                    continue;
                }
                for (Map.Entry<ByteKey, byte[]> e : input.getEntries().entrySet()) {
                    ByteKey key = e.getKey();
                    byte[] value = e.getValue();
                    if (key == null || key.bytes == null || value == null) {
                        continue;
                    }
                    // key.bytes is full key: [type][keydata...]
                    writeKeyValue(bos, key.bytes, value);
                }
                // input separator
                bos.write(0x00);
            }
        }

        // ----- Per-output maps -----
        if (outputMaps != null) {
            for (Map<ByteKey, byte[]> map : outputMaps) {
                if (map == null) {
                    bos.write(0x00);
                    continue;
                }
                for (Map.Entry<ByteKey, byte[]> e : map.entrySet()) {
                    ByteKey key = e.getKey();
                    byte[] value = e.getValue();
                    if (key == null || key.bytes == null || value == null) {
                        continue;
                    }
                    writeKeyValue(bos, key.bytes, value);
                }
                // output separator
                bos.write(0x00);
            }
        }

        return bos.toByteArray();
    }

    public String toBase64() throws IOException {
        return Base64.getEncoder().encodeToString(serialize());
    }

    // ─────────────────────────────────────────────────────────────────
    // Parsing implementation
    // ─────────────────────────────────────────────────────────────────

    private void doParse(byte[] bytes) throws IOException {
        DataInput in = new DataInputStream(new ByteArrayInputStream(bytes));

        // ── 1. verify magic ───────────────────────────────────────────
        byte[] magic = readExact(in, 5);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException(
                "Invalid PSBT magic: " + bytesToHex(magic));
        }

        // ── 2. global map ─────────────────────────────────────────────
        //globalExtras = new ArrayList<>();

        while (true) {
            int keyLen = readVarIntAsInt(in);
            if (keyLen == 0) break;   // end of global map

            byte[] keyBytes = readExact(in, keyLen);
            byte[] valBytes = readVarBytes(in);

            int keyType = keyBytes[0] & 0xFF;
            if (keyType == PSBT_GLOBAL_UNSIGNED_TX) {
                unsignedTxBytes = valBytes;
            } else {
                globalExtras.add(new KV(keyBytes, valBytes));
            }
        }

        if (unsignedTxBytes == null) {
            throw new IOException("PSBT missing global unsigned transaction");
        }

        // ── 3. count inputs and outputs from the unsigned tx ──────────
        //    This is critical — we MUST read inputCount and outputCount
        //    from the unsigned tx, NOT guess them.
        countTxInputsOutputs();

        // ── 4. per-input maps ─────────────────────────────────────────
        //inputMaps = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            inputMaps.add(readMap(in));
        }

        // ── 5. per-output maps ────────────────────────────────────────
        //outputMaps = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) {
            outputMaps.add(readMap(in));
        }
    }

    /**
     * Parses the unsigned tx bytes to extract input and output counts.
     *
     * Unsigned tx layout:
     *   version(4 LE)
     *   [0x00 0x01]   optional segwit marker+flag — NOT present in PSBT unsigned tx
     *   input_count   (varint)
     *   inputs:       each = prevTxid(32) + prevVout(4) + scriptSig_len(varint=0) + sequence(4)
     *   output_count  (varint)
     *   outputs:      each = value(8) + scriptPubKey_len(varint) + scriptPubKey
     *   locktime(4 LE)
     *
     * IMPORTANT: the unsigned tx in a PSBT MUST have empty scriptSigs
     *            and MUST NOT have segwit marker/flag.
     */
    private void countTxInputsOutputs() throws IOException {
        DataInputStream txIn = new DataInputStream(
            new ByteArrayInputStream(unsignedTxBytes));

        // version (4 bytes)
        readExact(txIn, 4);

        // check for segwit marker — BIP174 says unsigned tx must NOT have it
        // but be defensive
        txIn.mark(2);
        byte first = txIn.readByte();
        if (first == 0x00) {
            // segwit marker — skip flag byte too
            txIn.readByte();
        } else {
            txIn.reset();
        }

        // input count
        inputCount = readVarIntAsInt(txIn);

        // skip each input: prevTxid(32) + prevVout(4) + scriptSig(varint+bytes) + sequence(4)
        for (int i = 0; i < inputCount; i++) {
            readExact(txIn, 32 + 4);           // prevTxid + prevVout
            int scriptLen = readVarIntAsInt(txIn);
            if (scriptLen > 0) readExact(txIn, scriptLen);  // scriptSig (should be 0)
            readExact(txIn, 4);                // sequence
        }

        // output count
        outputCount = readVarIntAsInt(txIn);

        // we only needed the counts — stop here
    }

    /**
     * Reads one PSBT key-value map until a 0x00 separator.
     */
    private static Map<ByteKey, byte[]> readMap(DataInput in) throws IOException {
        Map<ByteKey, byte[]> map = new LinkedHashMap<>();
        while (true) {
            int keyLen = readVarIntAsInt(in);
            if (keyLen == 0) break;   // end of this map

            byte[] keyBytes = readExact(in, keyLen);
            byte[] valBytes = readVarBytes(in);
            map.put(new ByteKey(keyBytes), valBytes);
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────
    // Serialization helpers
    // ─────────────────────────────────────────────────────────────────
    
    private static void writeKeyValue(ByteArrayOutputStream out, byte[] fullKey, byte[] value) throws IOException {
        if (fullKey == null || value == null) {
            return; // skip malformed entries
        }

        // key = compactSize(len(fullKey)) + fullKey
        writeCompactSize(out, fullKey.length);
        out.write(fullKey);

        // value = compactSize(len(value)) + value
        writeCompactSize(out, value.length);
        out.write(value);
    }

    // ─────────────────────────────────────────────────────────────────
    // Low-level read helpers
    // ─────────────────────────────────────────────────────────────────

    private static byte[] readExact(DataInput in, int n) throws IOException {
        byte[] buf = new byte[n];
        try {
            in.readFully(buf);
        } catch (EOFException e) {
            throw new IOException(
                "Unexpected EOF reading " + n + " bytes: " + e.getMessage());
        }
        return buf;
    }

    private static byte[] readVarBytes(DataInput in) throws IOException {
        int len = readVarIntAsInt(in);
        return readExact(in, len);
    }

    /**
     * Reads a Bitcoin varint from the stream.
     *
     * < 0xFD         → 1 byte
     * == 0xFD        → next 2 bytes little-endian
     * == 0xFE        → next 4 bytes little-endian
     * == 0xFF        → next 8 bytes little-endian
     */
    private static long readVarInt(DataInput in) throws IOException {
        int first = in.readUnsignedByte();
        if (first < 0xFD) return first;
        if (first == 0xFD) {
            int lo = in.readUnsignedByte();
            int hi = in.readUnsignedByte();
            return lo | (hi << 8);
        }
        if (first == 0xFE) {
            long v = 0;
            for (int i = 0; i < 4; i++) v |= ((long) in.readUnsignedByte()) << (8 * i);
            return v;
        }
        // 0xFF
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) in.readUnsignedByte()) << (8 * i);
        return v;
    }

    private static int readVarIntAsInt(DataInput in) throws IOException {
        long v = readVarInt(in);
        if (v > Integer.MAX_VALUE) {
            throw new IOException("VarInt too large for int: " + v);
        }
        return (int) v;
    }    
    
    private static void writeCompactSize(ByteArrayOutputStream out, long value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("compactSize cannot be negative");
        }
        if (value < 0xFDL) {
            // 1 byte
            out.write((int) value);
        } else if (value <= 0xFFFFL) {
            // 0xFD + 2 bytes LE
            out.write(0xFD);
            out.write((int) (value & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
        } else if (value <= 0xFFFFFFFFL) {
            // 0xFE + 4 bytes LE
            out.write(0xFE);
            out.write((int) (value & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
            out.write((int) ((value >>> 24) & 0xFF));
        } else {
            // 0xFF + 8 bytes LE
            out.write(0xFF);
            out.write((int) (value & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
            out.write((int) ((value >>> 24) & 0xFF));
            out.write((int) ((value >>> 32) & 0xFF));
            out.write((int) ((value >>> 40) & 0xFF));
            out.write((int) ((value >>> 48) & 0xFF));
            out.write((int) ((value >>> 56) & 0xFF));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ParsedTx — unsigned transaction structure
    // ─────────────────────────────────────────────────────────────────

    public static final class ParsedTx {

        public final int            version;
        public final long           locktime;
        public final List<TxInput>  inputs;
        public final List<TxOutput> outputs;

        private ParsedTx(int version, long locktime,
                          List<TxInput> inputs, List<TxOutput> outputs) {
            this.version  = version;
            this.locktime = locktime;
            this.inputs   = Collections.unmodifiableList(inputs);
            this.outputs  = Collections.unmodifiableList(outputs);
        }

        public static ParsedTx parse(byte[] raw) throws IOException {
            DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(raw));

            // version
            int version = readInt32LE(in);

            // segwit check — skip marker+flag if present
            in.mark(2);
            int first = in.readUnsignedByte();
            if (first == 0x00) {
                in.readByte();   // skip flag
            } else {
                in.reset();
            }

            // inputs
            int inputCount = readVarIntAsInt(in);
            List<TxInput> inputs = new ArrayList<>(inputCount);
            for (int i = 0; i < inputCount; i++) {
                byte[] prevTxidLE = readExact(in, 32);
                int    prevVout   = readInt32LE(in);
                int    scriptLen  = readVarIntAsInt(in);
                if (scriptLen > 0) readExact(in, scriptLen);
                long   sequence   = readUint32LE(in);

                // txid: reverse the bytes for display
                byte[] reversed = Arrays.copyOf(prevTxidLE, 32);
                reverseInPlace(reversed);
                inputs.add(new TxInput(bytesToHex(reversed), prevVout,
                    prevTxidLE, sequence));
            }

            // outputs
            int outputCount = readVarIntAsInt(in);
            List<TxOutput> outputs = new ArrayList<>(outputCount);
            for (int i = 0; i < outputCount; i++) {
                long   valueSat  = readInt64LE(in);
                int    scriptLen = readVarIntAsInt(in);
                byte[] script    = readExact(in, scriptLen);
                outputs.add(new TxOutput(valueSat, script));
            }

            // locktime
            long locktime = readUint32LE(in);

            return new ParsedTx(version, locktime, inputs, outputs);
        }

        // ── read helpers ──────────────────────────────────────────────

        private static int readInt32LE(DataInputStream in) throws IOException {
            int v = 0;
            for (int i = 0; i < 4; i++) v |= in.readUnsignedByte() << (8 * i);
            return v;
        }

        private static long readUint32LE(DataInputStream in) throws IOException {
            long v = 0;
            for (int i = 0; i < 4; i++) v |= ((long) in.readUnsignedByte()) << (8 * i);
            return v;
        }

        private static long readInt64LE(DataInputStream in) throws IOException {
            long v = 0;
            for (int i = 0; i < 8; i++) v |= ((long) in.readUnsignedByte()) << (8 * i);
            return v;
        }

        private static byte[] readExact(DataInputStream in, int n) throws IOException {
            byte[] buf = new byte[n];
            in.readFully(buf);
            return buf;
        }

        private static int readVarIntAsInt(DataInputStream in) throws IOException {
            int first = in.readUnsignedByte();
            if (first < 0xFD) return first;
            if (first == 0xFD) {
                return in.readUnsignedByte() | (in.readUnsignedByte() << 8);
            }
            if (first == 0xFE) {
                long v = 0;
                for (int i = 0; i < 4; i++) v |= ((long)in.readUnsignedByte()) << (8*i);
                return (int) v;
            }
            throw new IOException("VarInt 0xFF not supported as int");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // TxInput / TxOutput
    // ─────────────────────────────────────────────────────────────────

    public static final class TxInput {
        public final String prevTxid;       // hex, display order
        public final int    prevVout;
        public final byte[] prevTxidLE;     // raw little-endian bytes
        public final long   sequence;

        TxInput(String prevTxid, int prevVout,
                byte[] prevTxidLE, long sequence) {
            this.prevTxid   = prevTxid;
            this.prevVout   = prevVout;
            this.prevTxidLE = prevTxidLE;
            this.sequence   = sequence;
        }
    }

    public static final class TxOutput {
        public final long   valueSat;
        public final byte[] scriptPubKey;

        TxOutput(long valueSat, byte[] scriptPubKey) {
            this.valueSat    = valueSat;
            this.scriptPubKey = scriptPubKey;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ByteKey — byte array wrapper for use as Map key
    // ─────────────────────────────────────────────────────────────────

    public static final class ByteKey {
        public final byte[] bytes;

        public ByteKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ByteKey)) return false;
            return Arrays.equals(bytes, ((ByteKey) o).bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // KV — global extra key-value pair
    // ─────────────────────────────────────────────────────────────────

    private static final class KV {
        final byte[] key;
        final byte[] value;
        KV(byte[] key, byte[] value) { this.key = key; this.value = value; }
    }

    // ─────────────────────────────────────────────────────────────────
    // Static byte utilities
    // ─────────────────────────────────────────────────────────────────

    private static void reverseInPlace(byte[] arr) {
        for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
            byte t = arr[i]; arr[i] = arr[j]; arr[j] = t;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
    
    public static final class PsbtInput {
        byte[] nonWitnessUtxo;
        byte[] witnessUtxo; // serialized UTXO output (amount+script)
        final Map<ByteArrayWrapper, byte[]> partialSigs = new LinkedHashMap<>();
        final Map<ByteArrayWrapper, Bip32Derivation> bip32Derivations = new LinkedHashMap<>();
        // Map<ByteArrayWrapper(pubkey), byte[] derivationValue>
        private final Map<ByteArrayWrapper, byte[]> bip32Derivs = new HashMap<>();
        
        //private final Map<PsbtKey, byte[]> entries;
        
        private static final int PSBT_IN_PARTIAL_SIG = 0x02;

        private final Map<ByteKey, byte[]> entries;

        public PsbtInput(Map<ByteKey, byte[]> entries) {
            // Wrap existing map (copy to be safe)
            this.entries = new LinkedHashMap<>(entries);
        }

        public Map<ByteKey, byte[]> getEntries() {
            return entries;
        }
        // pubkeyHex -> signatureHex
        private final Map<String, String> partialsigs =
                new LinkedHashMap<>();

        public Map<String, String> getPartialSigs() {
            return partialsigs;
        }

        public Map<ByteArrayWrapper, Bip32Derivation> getBip32Derivations() {
            return Collections.unmodifiableMap(bip32Derivations);
        }

        public void addBip32Deriv(byte[] pubKeyBytes, String fingerprintHex, String path) {
            // Convert hex string (e.g. "a986c79f") to 4‑byte array
            byte[] fingerprint4 = HexUtils.hexToBytes(fingerprintHex);
            if (fingerprint4.length != 4) {
                throw new IllegalArgumentException("Fingerprint hex must decode to 4 bytes, got " + fingerprint4.length);
            }

            byte[] value = PsbtUtils.buildBip32DerivValue(fingerprint4, path);
            bip32Derivs.put(new ByteArrayWrapper(pubKeyBytes), value);
        }
    
        /**
        * Add a partial signature to this PSBT input.
        *
        * pubKey = compressed pubkey bytes
        * signatureWithHashType = DER sig + sighash byte
         * @param pubKey
         * @param signatureWithHashType
        */
       public void addPartialSig(
               byte[] pubKey,
               byte[] signatureWithHashType
       ) {

           String pubHex =
                   HexUtils.bytesToHex(pubKey);

           String sigHex =
                   HexUtils.bytesToHex(signatureWithHashType);

           partialsigs.put(pubHex, sigHex);
       }

        public byte[] getNonWitnessUtxo() { return nonWitnessUtxo; }
        public byte[] getWitnessUtxo() { return witnessUtxo; }
        
        public void addPartialSignature(byte[] pubkey, byte[] sig) {
            if (pubkey == null || sig == null) {
                throw new IllegalArgumentException("pubkey and sig must not be null");
            }
            // type 0x02 + pubkey as key
            byte[] key = new byte[1 + pubkey.length];
            key[0] = (byte) PSBT_IN_PARTIAL_SIG;
            System.arraycopy(pubkey, 0, key, 1, pubkey.length);
            entries.put(new ByteKey(key), sig);
        }
             
        public Map<byte[], byte[]> getPartialSignatures() {
            Map<byte[], byte[]> result = new LinkedHashMap<>();

            for (Map.Entry<ByteKey, byte[]> e : entries.entrySet()) {
                ByteKey key = e.getKey();
                byte[] keyBytes = key.bytes; // raw PSBT key: [type][keydata...]

                if (keyBytes == null || keyBytes.length == 0) {
                    continue;
                }

                int type = keyBytes[0] & 0xFF;
                if (type == PSBT_IN_PARTIAL_SIG) {
                    // keydata is the pubkey (everything after the first byte)
                    byte[] pubkey = java.util.Arrays.copyOfRange(keyBytes, 1, keyBytes.length);
                    byte[] sig    = e.getValue();
                    result.put(pubkey, sig);
                }
            }

            return result;
        }
    }    
    
    public static final class PsbtOutput {
        // currently empty
    }

    public static final class Bip32Derivation {
        private final int fingerprint;
        private final List<Integer> path; // list of indices (some with hardened bit)

        public Bip32Derivation(int fingerprint, List<Integer> path) {
            this.fingerprint = fingerprint;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
        }

        public int getFingerprint() { return fingerprint; }
        public List<Integer> getPath() { return path; }
    }

    /** Byte-array key wrapper for maps with proper equals/hashCode. */
    public static final class ByteArrayWrapper {
        private final byte[] data;

        public ByteArrayWrapper(byte[] data) {
            this.data = data.clone();
        }

        public byte[] getData() { return data.clone(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayWrapper)) return false;
            ByteArrayWrapper that = (ByteArrayWrapper) o;
            return Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }    
    
    public static final class PsbtKey {
        private final int type;
        private final byte[] keyData;

        public PsbtKey(int type, byte[] keyData) {
            this.type = type;
            this.keyData = keyData != null ? keyData.clone() : new byte[0];
        }

        public int getType() {
            return type;
        }

        public byte[] getKeyData() {
            return keyData.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PsbtKey)) return false;
            PsbtKey other = (PsbtKey) o;
            if (this.type != other.type) return false;
            return java.util.Arrays.equals(this.keyData, other.keyData);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(type);
            result = 31 * result + java.util.Arrays.hashCode(keyData);
            return result;
        }
    }
}