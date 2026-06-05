package com.bitcoin.hdwallet.tranxcontrol;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.model.Psbt;
import com.bitcoin.hdwallet.crypto.Crypto;
import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.crypto.RFC6979;
import com.bitcoin.hdwallet.crypto.Secp256k1;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Arrays;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal SegWit v0 sighash + ECDSA signer, P2WPKH-focused.
 */
public final class TxSigner {

    // secp256k1 domain params (use same curve as Bip32HDWallet)
    private static final org.bouncycastle.crypto.params.ECDomainParameters SECP256K1;

    static {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECPoint G = params.getG();
        BigInteger n = params.getN();
        BigInteger h = params.getH();
        SECP256K1 = new ECDomainParameters(params.getCurve(), G, n, h);
    }

    private TxSigner() {}

    /**
     * Compute SegWit v0 sighash for input `inputIndex` of `psbt`,
     * assuming P2WPKH and SIGHASH_ALL.
     * @param psbt
     * @param inputIndex
     * @return 
     * @throws java.io.IOException
     */
    public static byte[] computeSegwitV0Sighash(Psbt psbt, int inputIndex) throws IOException {
        byte[] tx = psbt.getUnsignedTxBytes();

        // Parse tx to collect per-input and per-output data
        TxView view = TxView.parse(tx);

        // Get witness UTXO for this input (amount + scriptPubKey)
        Psbt.PsbtInput in = psbt.getInputs().get(inputIndex);
        if (in.getWitnessUtxo() == null) {
            throw new IOException("Witness UTXO missing; only P2WPKH with witness_utxo supported in this minimal impl");
        }
        byte[] witnessUtxo = in.getWitnessUtxo();
        // witness_utxo format: 8-byte amount (LE) + varint scriptLen + script bytes
        ByteArrayInputStream uin = new ByteArrayInputStream(witnessUtxo);
        long amount = readInt64LE(uin);
        int scriptLen = readVarInt(uin);
        byte[] scriptPubKey = readBytes(uin, scriptLen);

        // Build BIP-143 fields
        byte[] hashPrevouts = doubleSha256(view.getPrevoutsSerialized());
        byte[] hashSequence = doubleSha256(view.getSequencesSerialized());
        byte[] hashOutputs = doubleSha256(view.getOutputsSerialized());

        // Preimage:
        // nVersion || hashPrevouts || hashSequence || outpoint || scriptCode || value || nSequence || hashOutputs || nLockTime || sighashType
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        writeInt32LE(preimage, view.version);
        preimage.write(hashPrevouts);
        preimage.write(hashSequence);

        // outpoint
        preimage.write(view.inputs[inputIndex].prevTxid); // 32-byte, little-endian
        writeInt32LE(preimage, view.inputs[inputIndex].vout);

        // scriptCode (for P2WPKH: the standard P2PKH script)
        // scriptPubKey is typically: 0x00 0x14 <20-byte-hash160>
        // scriptCode for P2WPKH sighash is: 0x19 0x76 a9 14 <20-byte-hash160> 88 ac
        byte[] scriptCode = buildP2wpkhScriptCode(scriptPubKey);
        writeVarInt(preimage, scriptCode.length);
        preimage.write(scriptCode);

        // value (8 bytes)
        writeInt64LE(preimage, amount);

        // nSequence
        writeInt32LE(preimage, view.inputs[inputIndex].sequence);

        preimage.write(hashOutputs);
        writeInt32LE(preimage, view.locktime);

        int sighashType = 0x01; // SIGHASH_ALL
        writeInt32LE(preimage, sighashType);

        byte[] preimageBytes = preimage.toByteArray();
        return doubleSha256(preimageBytes);
    }
    
    public static byte[] computeSegwitV0Sighash(
        TxData tx,
        int inputIndex,
        byte[] scriptCode,
        long amountSat,
        int sighashType
    ) throws Exception {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        // -------------------------------------------------
        // VERSION
        // -------------------------------------------------

        writeUint32LE(out, tx.version());

        // -------------------------------------------------
        // hashPrevouts
        // -------------------------------------------------

        ByteArrayOutputStream prevouts =
                new ByteArrayOutputStream();

        for (TxInputData in : tx.inputs()) {

            prevouts.write(HexUtils.hexToBytes(in.prevTxId()));

            writeUint32LE(prevouts, in.prevVout());
        }

        byte[] hashPrevouts =
                Crypto.doubleSha256(
                        prevouts.toByteArray()
                );

        out.write(hashPrevouts);

        // -------------------------------------------------
        // hashSequence
        // -------------------------------------------------

        ByteArrayOutputStream seq =
                new ByteArrayOutputStream();

        for (TxInputData in : tx.inputs()) {
            writeUint32LE(seq, in.sequence());
        }

        byte[] hashSequence =
                Crypto.doubleSha256(
                        seq.toByteArray()
                );

        out.write(hashSequence);

        // -------------------------------------------------
        // outpoint being signed
        // -------------------------------------------------

        TxInputData input =
                tx.inputs().get(inputIndex);

        out.write(
                HexUtils.hexToBytes(input.prevTxId())                
        );

        writeUint32LE(out, input.prevVout());

        // -------------------------------------------------
        // scriptCode
        // -------------------------------------------------

        writeVarInt(out, scriptCode.length);
        out.write(scriptCode);

        // -------------------------------------------------
        // amount
        // -------------------------------------------------

        writeInt64LE(out, amountSat);

        // -------------------------------------------------
        // sequence
        // -------------------------------------------------

        writeUint32LE(out, input.sequence());

        // -------------------------------------------------
        // hashOutputs
        // -------------------------------------------------

        ByteArrayOutputStream outputs =
                new ByteArrayOutputStream();

        for (TxOutputData txOut : tx.outputs()) {

            writeInt64LE(outputs, txOut.valueSat());

            byte[] spk = HexUtils.hexToBytes(txOut.scriptHex());

            writeVarInt(outputs, spk.length);

            outputs.write(spk);
        }

        byte[] hashOutputs =
                Crypto.doubleSha256(
                        outputs.toByteArray()
                );

        out.write(hashOutputs);

        // -------------------------------------------------
        // locktime
        // -------------------------------------------------

        writeUint32LE(out, tx.locktime());

        // -------------------------------------------------
        // sighash type
        // -------------------------------------------------

        writeUint32LE(out, sighashType);

        return Crypto.doubleSha256(
                out.toByteArray()
        );
    }
    
    // Main entry: compute SegWit v0 sighash for inputIndex from decodepsbt JSON
    public static byte[] computeSegwitV0SighashFromDecoded(JSONObject decoded, int inputIndex) {
        JSONObject tx = decoded.getJSONObject("tx");

        int version = tx.getInt("version");
        long locktime = tx.getLong("locktime");

        JSONArray vin = tx.getJSONArray("vin");
        JSONArray vout = tx.getJSONArray("vout");

        // 1. Build hashPrevouts, hashSequence, hashOutputs
        byte[] hashPrevouts = buildHashPrevouts(vin);
        byte[] hashSequence = buildHashSequence(vin);
        byte[] hashOutputs  = buildHashOutputs(vout);

        // 2. Outpoint (txid + vout) of the input being signed
        JSONObject in = vin.getJSONObject(inputIndex);
        String prevTxidHex = in.getString("txid");
        int prevVout = in.getInt("vout");
        long sequence = in.getLong("sequence");

        byte[] prevTxidLE = reverseBytes(hexToBytes(prevTxidHex)); // txid is big-endian in JSON
        ByteArrayOutputStream outpoint = new ByteArrayOutputStream();
        try {
            outpoint.write(prevTxidLE);
            outpoint.write(intToLE(prevVout));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 3. scriptCode and value come from inputs[*].witness_utxo
        JSONObject inputDecoded = decoded.getJSONArray("inputs").getJSONObject(inputIndex);
        JSONObject witnessUtxo = inputDecoded.getJSONObject("witness_utxo");
        long valueSat = btcToSat(witnessUtxo.getDouble("amount"));

        String spkHex = witnessUtxo.getJSONObject("scriptPubKey").getString("hex");
        byte[] scriptPubKey = hexToBytes(spkHex);
        byte[] pubKeyHash20 = extractP2wpkhHash(scriptPubKey);
        byte[] scriptCode = buildP2wpkhScriptCode(pubKeyHash20);

        // 4. Build BIP143 preimage
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        try {
            // nVersion
            preimage.write(intToLE(version));
            // hashPrevouts
            preimage.write(hashPrevouts);
            // hashSequence
            preimage.write(hashSequence);
            // outpoint
            preimage.write(outpoint.toByteArray());
            // scriptCode
            preimage.write(compactSize(scriptCode.length));
            preimage.write(scriptCode);
            // value
            preimage.write(longToLE(valueSat));
            // nSequence
            preimage.write(intToLE((int) sequence));
            // hashOutputs
            preimage.write(hashOutputs);
            // nLockTime
            preimage.write(intToLE((int) locktime));
            // sighash type (SIGHASH_ALL)
            preimage.write(intToLE(1));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] preimageBytes = preimage.toByteArray();
        return doubleSha256(preimageBytes);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] buildHashPrevouts(JSONArray vin) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vin.length(); i++) {
                JSONObject in = vin.getJSONObject(i);
                String txidHex = in.getString("txid");
                int vout = in.getInt("vout");
                byte[] txidLE = reverseBytes(hexToBytes(txidHex));
                baos.write(txidLE);
                baos.write(intToLE(vout));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }

    private static byte[] buildHashSequence(JSONArray vin) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vin.length(); i++) {
                long sequence = vin.getJSONObject(i).getLong("sequence");
                baos.write(intToLE((int) sequence));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }

    private static byte[] buildHashOutputs(JSONArray vout) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vout.length(); i++) {
                JSONObject out = vout.getJSONObject(i);
                long valueSat = btcToSat(out.getDouble("value"));
                String spkHex = out.getJSONObject("scriptPubKey").getString("hex");
                byte[] spk = hexToBytes(spkHex);

                baos.write(longToLE(valueSat));
                baos.write(compactSize(spk.length));
                baos.write(spk);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }

    private static long btcToSat(double btc) {
        // For regtest dev use, this is fine. For production, use BigDecimal.
        return Math.round(btc * 100_000_000L);
    }

    private static byte[] extractP2wpkhHash(byte[] spk) {
        // P2WPKH: 0x00 0x14 {20-byte hash}
        if (spk.length != 22 || spk[0] != 0x00 || spk[1] != 0x14) {
            throw new IllegalArgumentException("Not P2WPKH scriptPubKey");
        }
        return Arrays.copyOfRange(spk, 2, 22);
    }

    private static byte[] buildP2wpkhScriptCode(byte[] pubKeyHash20) {
        // scriptCode = OP_DUP OP_HASH160 PUSH20 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(0x76); // OP_DUP
            baos.write(0xA9); // OP_HASH160
            baos.write(0x14); // PUSH 20 bytes
            baos.write(pubKeyHash20);
            baos.write(0x88); // OP_EQUALVERIFY
            baos.write(0xAC); // OP_CHECKSIG
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static byte[] compactSize(int len) {
        if (len < 0xFD) {
            return new byte[]{(byte) len};
        } else if (len <= 0xFFFF) {
            return new byte[]{(byte) 0xFD, (byte) (len & 0xFF), (byte) ((len >>> 8) & 0xFF)};
        } else if (len <= 0xFFFF_FFFF) {
            return new byte[]{
                    (byte) 0xFE,
                    (byte) (len & 0xFF),
                    (byte) ((len >>> 8) & 0xFF),
                    (byte) ((len >>> 16) & 0xFF),
                    (byte) ((len >>> 24) & 0xFF)
            };
        } else {
            throw new IllegalArgumentException("Length too large for compactSize");
        }
    }

    private static byte[] intToLE(int value) {
        return new byte[]{
                (byte) (value       & 0xFF),
                (byte) (value >>> 8 & 0xFF),
                (byte) (value >>>16 & 0xFF),
                (byte) (value >>>24 & 0xFF)
        };
    }

    private static byte[] longToLE(long value) {
        return new byte[]{
                (byte) (value        & 0xFF),
                (byte) (value >>>  8 & 0xFF),
                (byte) (value >>> 16 & 0xFF),
                (byte) (value >>> 24 & 0xFF),
                (byte) (value >>> 32 & 0xFF),
                (byte) (value >>> 40 & 0xFF),
                (byte) (value >>> 48 & 0xFF),
                (byte) (value >>> 56 & 0xFF),
        };
    }

    private static byte[] doubleSha256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(sha256.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private static byte[] reverseBytes(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    
    private static byte[] derEncodeSignature(BigInteger r, BigInteger s) {
        // Convert r and s to bytes without sign bit confusion
        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray();

        // If highest bit is set, prepend 0x00 to make them positive for ASN.1
        if ((rBytes[0] & 0x80) != 0) {
            byte[] tmp = new byte[rBytes.length + 1];
            System.arraycopy(rBytes, 0, tmp, 1, rBytes.length);
            rBytes = tmp;
        }
        if ((sBytes[0] & 0x80) != 0) {
            byte[] tmp = new byte[sBytes.length + 1];
            System.arraycopy(sBytes, 0, tmp, 1, sBytes.length);
            sBytes = tmp;
        }

        int len = 2               // 0x02 <len(r)>
                + rBytes.length
                + 2               // 0x02 <len(s)>
                + sBytes.length;

        byte[] der = new byte[2 + len]; // 0x30 <len> <r-blob> <s-blob>
        int pos = 0;
        der[pos++] = 0x30;               // SEQUENCE
        der[pos++] = (byte) len;

        der[pos++] = 0x02;               // INTEGER
        der[pos++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, pos, rBytes.length);
        pos += rBytes.length;

        der[pos++] = 0x02;               // INTEGER
        der[pos++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, pos, sBytes.length);
        // pos += sBytes.length; // not needed

        return der;
    }

    // ----- Helpers: TxView parsing, scriptCode, varint, int encodings, hashes -----

    private static final class TxView {
        int version;
        InputView[] inputs;
        OutputView[] outputs;
        int locktime;

        static TxView parse(byte[] tx) throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(tx);
            TxView v = new TxView();
            v.version = (int) readInt32LE(in);

            int marker = in.read();
            int flag = -1;
            if (marker == 0) {
                flag = in.read();
            } else {
                in.reset();
            }

            int vinCount = readVarInt(in);
            v.inputs = new InputView[vinCount];
            for (int i = 0; i < vinCount; i++) {
                InputView iv = new InputView();
                iv.prevTxid = readBytes(in, 32);
                iv.vout = (int) readInt32LE(in);
                int scriptLen = readVarInt(in);
                iv.scriptSig = readBytes(in, scriptLen);
                iv.sequence = (int) readInt32LE(in);
                v.inputs[i] = iv;
            }

            int voutCount = readVarInt(in);
            v.outputs = new OutputView[voutCount];
            for (int i = 0; i < voutCount; i++) {
                OutputView ov = new OutputView();
                ov.value = readInt64LE(in);
                int scriptLen = readVarInt(in);
                ov.scriptPubKey = readBytes(in, scriptLen);
                v.outputs[i] = ov;
            }

            v.locktime = (int) readInt32LE(in);
            return v;
        }

        byte[] getPrevoutsSerialized() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (InputView iv : inputs) {
                out.write(iv.prevTxid);
                writeInt32LE(out, iv.vout);
            }
            return out.toByteArray();
        }

        byte[] getSequencesSerialized() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (InputView iv : inputs) {
                writeInt32LE(out, iv.sequence);
            }
            return out.toByteArray();
        }

        byte[] getOutputsSerialized() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (OutputView ov : outputs) {
                writeInt64LE(out, ov.value);
                writeVarInt(out, ov.scriptPubKey.length);
                out.write(ov.scriptPubKey);
            }
            return out.toByteArray();
        }
    }

    private static final class InputView {
        byte[] prevTxid;
        int vout;
        byte[] scriptSig;
        int sequence;
    }

    private static final class OutputView {
        long value;
        byte[] scriptPubKey;
    }
    
    private static long readInt64LE(ByteArrayInputStream in) throws IOException {
        byte[] b = readBytes(in, 8);
        return ((long) (b[0] & 0xFF)) |
               ((long) (b[1] & 0xFF) << 8) |
               ((long) (b[2] & 0xFF) << 16) |
               ((long) (b[3] & 0xFF) << 24) |
               ((long) (b[4] & 0xFF) << 32) |
               ((long) (b[5] & 0xFF) << 40) |
               ((long) (b[6] & 0xFF) << 48) |
               ((long) (b[7] & 0xFF) << 56);
    }

    private static long readInt32LE(ByteArrayInputStream in) throws IOException {
        byte[] b = readBytes(in, 4);
        return ((long) (b[0] & 0xFF)) |
               ((long) (b[1] & 0xFF) << 8) |
               ((long) (b[2] & 0xFF) << 16) |
               ((long) (b[3] & 0xFF) << 24);
    }

    private static byte[] readBytes(ByteArrayInputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int n = in.read(buf);
        if (n != len) throw new IOException("Unexpected EOF");
        return buf;
    }

    private static int readVarInt(ByteArrayInputStream in) throws IOException {
        int first = in.read();
        if (first < 0) throw new IOException("Unexpected EOF in varint");

        if (first < 0xfd) {
            return first;
        } else if (first == 0xfd) {
            int b0 = in.read();
            int b1 = in.read();
            if ((b0 | b1) < 0) throw new IOException("Unexpected EOF in varint");
            return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
        } else if (first == 0xfe) {
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF in varint");
            return (b0 & 0xFF) |
                   ((b1 & 0xFF) << 8) |
                   ((b2 & 0xFF) << 16) |
                   ((b3 & 0xFF) << 24);
        } else {
            throw new IOException("Varint > 32 bits not supported");
        }
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeInt64LE(ByteArrayOutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 32) & 0xFF));
        out.write((int) ((value >>> 40) & 0xFF));
        out.write((int) ((value >>> 48) & 0xFF));
        out.write((int) ((value >>> 56) & 0xFF));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) throws IOException {
        if (value < 0xfd) {
            out.write(value);
        } else if (value <= 0xffff) {
            out.write(0xfd);
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
        } else {
            out.write(0xfe);
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 24) & 0xFF);
        }
    }    
    
    public static void writeUint32LE(
        OutputStream out,
        long value
    ) throws IOException {

        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }
    
    public static void writeInt64LE(
        OutputStream out,
        long value
    ) throws IOException {

        for (int i = 0; i < 8; i++) {

            out.write(
                    (int) ((value >> (8 * i)) & 0xff)
            );
        }
    }
    
    public static void writeVarInt(
        OutputStream out,
        long value
    ) throws IOException {

        if (value < 0xfd) {

            out.write((int) value);

        } else if (value <= 0xffff) {

            out.write(0xfd);

            out.write((int) (value & 0xff));
            out.write((int) ((value >> 8) & 0xff));

        } else if (value <= 0xffffffffL) {

            out.write(0xfe);

            writeUint32LE(out, value);

        } else {

            out.write(0xff);

            writeInt64LE(out, value);
        }
    }
       
    public static byte[] buildP2WPKHScriptCode(
        byte[] pubKeyHash
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(0x76);
        out.write(0xa9);
        out.write(0x14);

        out.write(pubKeyHash, 0, pubKeyHash.length);

        out.write(0x88);
        out.write(0xac);

        return out.toByteArray();
    }

    public static byte[] signECDSA(
            byte[] privKeyBytes,
            byte[] sighash
    ) throws Exception {

        Secp256k1 curve = Secp256k1.get();

        BigInteger d =
            new BigInteger(1, privKeyBytes);

        BigInteger z =
            new BigInteger(1, sighash);

        BigInteger k =
            RFC6979.generateK(
                curve.getN(),                
                sighash,
                d
            );
        
        /*
         BigInteger privKey,
            byte[] hash,
            BigInteger n
        */

        // R = k * G
        ECPoint R =
            curve.getG().multiply(k).normalize();

        BigInteger r =
            R.getAffineXCoord()
             .toBigInteger()
             .mod(curve.getN());

        BigInteger s =
            k.modInverse(curve.getN())
             .multiply(
                 z.add(r.multiply(d))
             )
             .mod(curve.getN());

        // low-S normalization
        BigInteger halfN =
            curve.getN().shiftRight(1);

        if (s.compareTo(halfN) > 0) {
            s = curve.getN().subtract(s);
        }

        return encodeDer(r, s);
    }
    
    public static byte[] encodeDer(
        BigInteger r,
        BigInteger s
    ) {

        byte[] rBytes =
                toUnsigned(r);

        byte[] sBytes =
                toUnsigned(s);

        int totalLength =
                2 + rBytes.length +
                2 + sBytes.length;

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(0x30);
        out.write(totalLength);

        out.write(0x02);
        out.write(rBytes.length);
        out.write(rBytes, 0, rBytes.length);

        out.write(0x02);
        out.write(sBytes.length);
        out.write(sBytes, 0, sBytes.length);

        return out.toByteArray();
    }
    
    private static byte[] toUnsigned(
        BigInteger v
    ) {

        byte[] b =
                v.toByteArray();

        /*
         * remove leading zero
         */

        if (b.length > 1 &&
                b[0] == 0x00) {

            b = Arrays.copyOfRange(
                    b,
                    1,
                    b.length
            );
        }

        /*
         * prepend zero if high bit set
         */

        if ((b[0] & 0x80) != 0) {

            byte[] padded =
                    new byte[b.length + 1];

            System.arraycopy(
                    b,
                    0,
                    padded,
                    1,
                    b.length
            );

            padded[0] = 0x00;

            return padded;
        }

        return b;
    }    
    
    /**
     * Sign given sighash with secp256k1 ECDSA (low-S, DER encoding + sighash type).
     * @param privKey
     * @param sighash
     * @return 
     */
    public static byte[] signSchnorrOrEcdsa(byte[] privKey, byte[] sighash) {
        // ECDSA over secp256k1
        BigInteger d = new BigInteger(1, privKey);
        ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(d, SECP256K1);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ParametersWithRandom(privParams, new SecureRandom()));
        BigInteger[] rs = signer.generateSignature(sighash);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        // enforce low-S
        BigInteger halfN = SECP256K1.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) {
            s = SECP256K1.getN().subtract(s);
        }

        byte[] der = derEncodeSignature(r, s);
        // append sighash type 0x01
        byte[] sigWithType = new byte[der.length + 1];
        System.arraycopy(der, 0, sigWithType, 0, der.length);
        sigWithType[der.length] = 0x01;
 

        return sigWithType;
    }    
}