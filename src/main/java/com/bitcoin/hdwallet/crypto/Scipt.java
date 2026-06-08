package com.bitcoin.hdwallet.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 *
 * @author DAOMOSDA
 */

// Script.java — add SegWit script types
public class Script {
    
    private byte[] scriptBytes;
    private ScriptType type;
    
    public enum ScriptType {
        P2PKH,      // legacy: OP_DUP OP_HASH160 <hash> OP_EQUALVERIFY OP_CHECKSIG
        P2WPKH,     // native segwit: OP_0 <20-byte-hash>
        P2SH_P2WPKH // wrapped segwit: OP_HASH160 <script-hash> OP_EQUAL
    }
    
    public Script(byte[] scriptBytes, ScriptType type) {
        this.scriptBytes = scriptBytes;
        this.type = type;
    }
    
    // Existing P2PKH factory (keep backward compat)
    public static Script p2pkh(byte[] pubKeyHash) {
        // OP_DUP(0x76) OP_HASH160(0xa9) 0x14 <hash160> OP_EQUALVERIFY(0x88) OP_CHECKSIG(0xac)
        byte[] script = new byte[25];
        script[0] = 0x76;
        script[1] = (byte) 0xa9;
        script[2] = 0x14;
        System.arraycopy(pubKeyHash, 0, script, 3, 20);
        script[23] = (byte) 0x88;
        script[24] = (byte) 0xac;
        return new Script(script, ScriptType.P2PKH);
    }
    
    // NEW: Native SegWit P2WPKH
    // scriptPubKey: OP_0 <20-byte-witness-program>
    public static Script p2wpkh(byte[] pubKeyHash) {
        byte[] script = new byte[22];
        script[0] = 0x00; // OP_0 (witness version)
        script[1] = 0x14; // push 20 bytes
        System.arraycopy(pubKeyHash, 0, script, 2, 20);
        return new Script(script, ScriptType.P2WPKH);
    }
    
    // NEW: P2SH-wrapped P2WPKH (for compatibility with legacy wallets)
    public static Script p2shP2wpkh(byte[] redeemScriptHash) {
        byte[] script = new byte[23];
        script[0] = (byte) 0xa9; // OP_HASH160
        script[1] = 0x14;        // push 20 bytes
        System.arraycopy(redeemScriptHash, 0, script, 2, 20);
        script[22] = (byte) 0x87; // OP_EQUAL
        return new Script(script, ScriptType.P2SH_P2WPKH);
    }
    
    public boolean isSegWit() {
        return type == ScriptType.P2WPKH || type == ScriptType.P2SH_P2WPKH;
    }
    
    public boolean isUnlockableBy(byte[] pubKeyBytes) {
        byte[] pubKeyHash = hash160(pubKeyBytes);
        switch (type) {
            case P2PKH:
                // existing logic — bytes 3..22 are the hash
                if (scriptBytes.length != 25) return false;
                return Arrays.equals(
                    Arrays.copyOfRange(scriptBytes, 3, 23), pubKeyHash);
            case P2WPKH:
                // bytes 2..21 are the witness program
                if (scriptBytes.length != 22) return false;
                return Arrays.equals(
                    Arrays.copyOfRange(scriptBytes, 2, 22), pubKeyHash);
            case P2SH_P2WPKH:
                // hash the redeem script p2wpkh(pubKeyHash) and compare
                byte[] redeemScript = p2wpkh(pubKeyHash).getScriptBytes();
                byte[] redeemScriptHash = hash160(redeemScript);
                return Arrays.equals(
                    Arrays.copyOfRange(scriptBytes, 2, 22), redeemScriptHash);
            default:
                return false;
        }
    }
    
    /*
    public static String p2wpkhScriptToAddress(Script script, boolean testnet) {
        byte[] s = script.getScriptBytes();   // raw script bytes

        // P2WPKH ScriptPubKey is: OP_0 (0x00), PUSH_20 (0x14), then 20 bytes [web:103][web:133]
        if (s.length != 22 || s[0] != 0x00 || (s[1] & 0xFF) != 0x14) {
            throw new IllegalArgumentException("Not a P2WPKH script");
        }

        byte[] hash160 = Arrays.copyOfRange(s, 2, 22); // 20-byte witness program

        String hrp = testnet ? "tb" : "bc";
        SegWitAddress seg = new SegWitAddress(hrp, 0, hash160, testnet);
        return seg.toBech32Address();
    }
    */
    
    public static String p2wpkhScriptToAddress(Script script, SegWitAddress.Network network) {
        byte[] s = script.getScriptBytes();   // raw script bytes

        // P2WPKH ScriptPubKey: OP_0 (0x00) 0x14 <20-byte-hash>
        if (s.length != 22 || s[0] != 0x00 || (s[1] & 0xFF) != 0x14) {
            throw new IllegalArgumentException("Not a P2WPKH script");
        }

        byte[] hash160 = Arrays.copyOfRange(s, 2, 22); // 20-byte witness program

        String hrp = hrpForNetwork(network);  // "bc" / "tb" / "bcrt"
        SegWitAddress seg = new SegWitAddress(hrp, 0, hash160, network);
        return seg.toBech32Address();
    }
    
    /*
    public static Script p2wpkhFromAddress(String recipientAddress, boolean testnet) {
        SegWitAddressUtil.WitnessProgram wp = SegWitAddressUtil.decodeAddress(recipientAddress, testnet);

        if (wp.version != 0 || wp.program.length != 20) {
            throw new IllegalArgumentException("Address is not P2WPKH (v0, 20-byte program)");
        }

        // script: 0x00 0x14 {20-byte program}
        byte[] script = new byte[2 + wp.program.length];
        script[0] = 0x00;            // OP_0 / version 0
        script[1] = 0x14;            // PUSH 20 bytes

        System.arraycopy(wp.program, 0, script, 2, wp.program.length);

        return new Script(script, ScriptType.P2WPKH);   // Your Script class taking raw bytes
    }
    */
    
    public static Script p2wpkhFromAddress(String recipientAddress,
                                       SegWitAddress.Network network) {

        SegWitAddressUtil.WitnessProgram wp =
            SegWitAddressUtil.decodeAddress(recipientAddress, network);

        if (wp.version != 0 || wp.program.length != 20) {
            throw new IllegalArgumentException("Address is not P2WPKH (v0, 20-byte program)");
        }

        // script: 0x00 0x14 {20-byte program}
        byte[] script = new byte[2 + wp.program.length];
        script[0] = 0x00;            // OP_0 / version 0
        script[1] = 0x14;            // PUSH 20 bytes
        System.arraycopy(wp.program, 0, script, 2, wp.program.length);

        return new Script(script, ScriptType.P2WPKH);
    }
    
    public static byte[] hash160(byte[] data) {
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(data);
            MessageDigest ripemd = MessageDigest.getInstance("RIPEMD160",
                new BouncyCastleProvider());
            return ripemd.digest(sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("hash160 failed", e);
        }
    }
    
    private static String hrpForNetwork(SegWitAddress.Network network) {
        switch (network) {
            case MAINNET:
                return "bc";
            case TESTNET:
                return "tb";
            case REGTEST:
                return "bcrt";
            default:
                throw new IllegalArgumentException("Unknown network: " + network);
        }
    }
    
    public byte[] getScriptBytes() { return scriptBytes; }
    public ScriptType getType()    { return type; }
}
