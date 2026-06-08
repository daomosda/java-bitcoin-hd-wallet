package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.Bip32HDWallet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class SegWitAddress {

    //private static final byte[] BITCOIN_MAINNET = { (byte) 0x04, (byte) 0x88, (byte) 0xB2, (byte) 0x1E };
    //private static final byte[] BITCOIN_TESTNET = { (byte) 0x04, (byte) 0x35, (byte) 0x87, (byte) 0xCF };
    
    // Prefix for bech32 encoding
    private static final String MAINNET_PREFIX = "bc";
    private static final String TESTNET_PREFIX = "tb";
    private static final String REGTEST_PREFIX = "bcrt";

    // Witness versions
    public static final int WITNESS_V0 = 0;
    public static final int WITNESS_V1 = 1; // Taproot

    // Script types
    public static final String P2WPKH = "p2wpkh";
    public static final String P2SH_P2WPKH = "p2sh-p2wpkh";
    public static final String P2TR = "p2tr";
    public static final String P2PKH = "p2pkh";
 
    public enum Network {
        MAINNET, TESTNET, REGTEST
    }

    private final String hrp;
    private final int version;
    private final byte[] program;
    private final Network network;

    public SegWitAddress(String hrp, int version, byte[] program, Network network) {
        this.hrp = hrp;
        this.version = version;
        this.program = program;
        this.network = network;
    }

    public int getWitnessVersion() {
        return version;
    }

    public byte[] getWitnessProgram() {
        return program.clone();
    }

    public String getHrp() {
        return hrp;
    }

    public Network getNetwork() {
        return network;
    }
 
    public String toBech32Address() {
        // 1. Build 5-bit payload: [version][program as 5-bit groups]
        byte[] prog5 = Bech32.convertBits(program, 8, 5, true);
        if (prog5 == null) {
            throw new IllegalStateException("convertBits failed");
        }

        byte[] data = new byte[1 + prog5.length];
        data[0] = (byte) version;              // 5-bit witness version
        System.arraycopy(prog5, 0, data, 1, prog5.length);

        // 2. Choose encoding based on version (BIP350)
        Bech32.Encoding enc = (version == 0)
                ? Bech32.Encoding.BECH32
                : Bech32.Encoding.BECH32M;

        return Bech32.encode(hrp, data);   //, enc);
    }
       
    public static SegWitAddress fromString(String addr, Network network) {
        Bech32.Decoded decoded = Bech32.decode(addr);

        // Expected HRP per network
        String expectedHrp;
        switch (network) {
            case MAINNET:
                expectedHrp = MAINNET_PREFIX; // "bc"
                break;
            case TESTNET:
                expectedHrp = TESTNET_PREFIX; // "tb"
                break;
            case REGTEST:
                expectedHrp = REGTEST_PREFIX; // "bcrt"
                break;
            default:
                throw new IllegalArgumentException("Unknown network: " + network);
        }

        if (!decoded.hrp.equals(expectedHrp)) {
            throw new IllegalArgumentException(
                    "Invalid HRP: expected " + expectedHrp + " but got " + decoded.hrp);
        }

        byte[] data = decoded.data;             // 5-bit groups (no checksum)
        if (data.length < 1) {
            throw new IllegalArgumentException("Invalid SegWit address: no witness version");
        }

        int witver = data[0] & 0x1f;            // witness version (0–16) in first 5 bits
        if (witver < 0 || witver > 16) {
            throw new IllegalArgumentException("Invalid witness version: " + witver);
        }

        // Convert remaining 5-bit groups to 8-bit witness program bytes
        byte[] program = Bech32.convertBits(
                Arrays.copyOfRange(data, 1, data.length),
                5, 8, false
        );

        if (program == null) {
            throw new IllegalArgumentException("Invalid SegWit address: convertBits failed");
        }

        if (program.length < 2 || program.length > 40) {
            throw new IllegalArgumentException("Invalid witness program length: " + program.length);
        }

        if (witver == 0 && program.length != 20 && program.length != 32) {
            throw new IllegalArgumentException(
                    "Invalid v0 witness program length (must be 20 or 32, got " + program.length + ")");
        }

        // BIP350 encoding rules
        boolean isBech32  = decoded.encoding == Bech32.Encoding.BECH32;
        boolean isBech32m = decoded.encoding == Bech32.Encoding.BECH32M;

        if (witver == 0 && !isBech32) {
            throw new IllegalArgumentException("v0 address must use Bech32 encoding");
        }
        if (witver != 0 && !isBech32m) {
            throw new IllegalArgumentException("v1+ address must use Bech32m encoding");
        }

        return new SegWitAddress(decoded.hrp, witver, program, network);
    }

    public static String fromPublicKeyP2WPKH(byte[] publicKey, Network network) {
        if (publicKey.length != 33 && publicKey.length != 65) {
            throw new IllegalArgumentException("Invalid public key length");
        }

        if (publicKey.length == 65) {
            publicKey = compressPublicKey(publicKey);
        }

        byte[] hash160 = hash160(publicKey);

        // Witness program for Bech32: version=0 + program=20-byte hash160
        byte[] prog5 = Bech32.convertBits(hash160, 8, 5, true);
        if (prog5 == null) {
            throw new IllegalStateException("convertBits failed");
        }

        byte[] data = new byte[1 + prog5.length];
        data[0] = 0; // witness version 0
        System.arraycopy(prog5, 0, data, 1, prog5.length);

        final String hrp;
        switch (network) {
            case REGTEST:
                hrp = REGTEST_PREFIX;   // "bcrt" [web:194]
                break;
            case TESTNET:
                hrp = TESTNET_PREFIX;   // "tb"
                break;
            case MAINNET:
            default:
                hrp = MAINNET_PREFIX;   // "bc"
                break;
        }

        return Bech32.encode(hrp, data); 
    }
    
    public static String fromPubKeyCompressed(byte[] pubKey, String type, Network network) {
        if (!"p2wpkh".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Only p2wpkh supported in this helper");
        }
        if (pubKey.length != 33 || (pubKey[0] != 0x02 && pubKey[0] != 0x03)) {
            throw new IllegalArgumentException("Invalid compressed pubkey");
        }

        // Reuse the canonical P2WPKH implementation
        return fromPublicKeyP2WPKH(pubKey, network);        
    }
       

    public static String fromPublicKeyP2SHP2WPKH(byte[] publicKey, Network network) {
        if (publicKey.length != 33 && publicKey.length != 65) {
            throw new IllegalArgumentException("Invalid public key length");
        }

        // Compress if uncompressed
        if (publicKey.length == 65) {
            publicKey = compressPublicKey(publicKey);
        }

        // Hash160 of public key
        byte[] hash160 = hash160(publicKey);

        // Create P2WPKH witness script: OP_0 <20-byte-hash>
        byte[] witnessScript = new byte[22];
        witnessScript[0] = (byte) 0x00; // OP_0
        witnessScript[1] = (byte) 0x14; // Push 20 bytes
        System.arraycopy(hash160, 0, witnessScript, 2, 20);

        // Hash160 of witness script for P2SH
        byte[] scriptHash = hash160(witnessScript);

        // P2SH version byte:
        // - mainnet: 0x05
        // - testnet/regtest: 0xC4
        byte version;
        switch (network) {
            case MAINNET:
                version = (byte) 0x05;
                break;
            case TESTNET:
            case REGTEST:
                version = (byte) 0xC4;
                break;
            default:
                throw new IllegalArgumentException("Unknown network: " + network);
        }

        byte[] versioned = new byte[21];
        versioned[0] = version;
        System.arraycopy(scriptHash, 0, versioned, 1, 20);

        return Base58.encodeChecked(versioned);
    }

    public static String fromPublicKeyP2TR(byte[] publicKey, Network network) {
        if (publicKey.length != 33) {
            throw new IllegalArgumentException("Taproot requires compressed public key");
        }

        // BIP86: tweak simplified placeholder
        byte[] tweak = taprootTweak(publicKey);
        byte[] tweakedPubKey = tweakPublicKey(publicKey, tweak); // 32-byte x-only

        byte[] prog5 = Bech32.convertBits(tweakedPubKey, 8, 5, true);
        if (prog5 == null) {
            throw new IllegalStateException("convertBits failed");
        }

        byte[] data = new byte[1 + prog5.length];
        data[0] = 1; // witness version 1
        System.arraycopy(prog5, 0, data, 1, prog5.length);

        final String hrp;
        switch (network) {
            case REGTEST:
                hrp = REGTEST_PREFIX;
                break;
            case TESTNET:
                hrp = TESTNET_PREFIX;
                break;
            case MAINNET:
            default:
                hrp = MAINNET_PREFIX;
                break;
        }

        return Bech32.encode(hrp, data);   //, Bech32.Encoding.BECH32M);
    }

    public static String fromPublicKeyP2PKH(byte[] publicKey, Network network) {
        if (publicKey.length != 33 && publicKey.length != 65) {
            throw new IllegalArgumentException("Invalid public key length");
        }

        // Compress if uncompressed
        if (publicKey.length == 65) {
            publicKey = compressPublicKey(publicKey);
        }

        byte[] hash160 = hash160(publicKey);

        // P2PKH version byte:
        // - mainnet: 0x00
        // - testnet/regtest: 0x6F
        byte version;
        switch (network) {
            case MAINNET:
                version = (byte) 0x00;
                break;
            case TESTNET:
            case REGTEST:
                version = (byte) 0x6F;
                break;
            default:
                throw new IllegalArgumentException("Unknown network: " + network);
        }

        byte[] versioned = new byte[21];
        versioned[0] = version;
        System.arraycopy(hash160, 0, versioned, 1, 20);

        return Base58.encodeChecked(versioned);
    }

    public static String fromHDKey(Bip32HDWallet hdKey,
                                   String addressType,
                                   Network network) {
        byte[] publicKey = hdKey.getPublicKeyCompressed();

        switch (addressType.toLowerCase()) {
            case P2WPKH:
                return fromPublicKeyP2WPKH(publicKey, network);
            case P2SH_P2WPKH:
                return fromPublicKeyP2SHP2WPKH(publicKey, network); // testnet flag
            case P2TR:
                return fromPublicKeyP2TR(publicKey, network);
            case P2PKH:
                return fromPublicKeyP2PKH(publicKey, network); // legacy bool retained
            default:
                throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
    }

    /**
     * Get appropriate derivation path for address type
     * @param addressType
     * @param coinType
     * @param account
     * @param change
     * @param index
     * @return 
     */
    public static String getDerivationPath(String addressType, int coinType, int account, int change, int index) {
        switch (addressType.toLowerCase()) {
            case P2PKH:
                return String.format("m/44'/%d'/%d'/%d/%d", coinType, account, change, index);
            case P2SH_P2WPKH:
                return String.format("m/49'/%d'/%d'/%d/%d", coinType, account, change, index);
            case P2WPKH:
                return String.format("m/84'/%d'/%d'/%d/%d", coinType, account, change, index);
            case P2TR:
                return String.format("m/86'/%d'/%d'/%d/%d", coinType, account, change, index);
            default:
                throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
    }

    /**
     * Create scriptPubKey for address type
     */
    public static byte[] createScriptPubKey(byte[] publicKey, String addressType) {
        if (publicKey.length != 33) {
            publicKey = compressPublicKey(publicKey);
        }

        byte[] hash160 = hash160(publicKey);

        switch (addressType.toLowerCase()) {
            case P2WPKH:
                // OP_0 <20-byte-hash>
                byte[] wpkh = new byte[22];
                wpkh[0] = (byte) 0x00;
                wpkh[1] = (byte) 0x14;
                System.arraycopy(hash160, 0, wpkh, 2, 20);
                return wpkh;

            case P2SH_P2WPKH:
                // Return P2SH scriptPubKey (wrapper)
                byte[] witnessScript = new byte[22];
                witnessScript[0] = (byte) 0x00;
                witnessScript[1] = (byte) 0x14;
                System.arraycopy(hash160, 0, witnessScript, 2, 20);
                byte[] scriptHash = hash160(witnessScript);
                
                byte[] p2sh = new byte[23];
                p2sh[0] = (byte) 0xA9; // OP_HASH160
                p2sh[1] = (byte) 0x14; // Push 20 bytes
                System.arraycopy(scriptHash, 0, p2sh, 2, 20);
                p2sh[22] = (byte) 0x87; // OP_EQUAL
                return p2sh;

            case P2TR:
                // OP_1 <32-byte-tweaked-pubkey>
                byte[] tweak = taprootTweak(publicKey);
                byte[] tweakedPubKey = tweakPublicKey(publicKey, tweak);
                
                byte[] p2tr = new byte[34];
                p2tr[0] = (byte) 0x51; // OP_1
                p2tr[1] = (byte) 0x20; // Push 32 bytes
                System.arraycopy(tweakedPubKey, 0, p2tr, 2, 32);
                return p2tr;

            case P2PKH:
                // OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
                byte[] p2pkh = new byte[25];
                p2pkh[0] = (byte) 0x76; // OP_DUP
                p2pkh[1] = (byte) 0xA9; // OP_HASH160
                p2pkh[2] = (byte) 0x14; // Push 20 bytes
                System.arraycopy(hash160, 0, p2pkh, 3, 20);
                p2pkh[23] = (byte) 0x88; // OP_EQUALVERIFY
                p2pkh[24] = (byte) 0xAC; // OP_CHECKSIG
                return p2pkh;

            default:
                throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
    }
    
    private static byte[] hash160(byte[] data) {
        return Crypto.hash160(data);
    }

    // ============ Taproot Helper Methods ============

    private static byte[] taprootTweak(byte[] publicKey) {
        try {
            // For BIP86 (key path spend only), tweak = hash(publicKey)
            byte[] data = new byte[32];
            System.arraycopy(publicKey, 1, data, 0, 32);
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static byte[] tweakPublicKey(byte[] publicKey, byte[] tweak) {
        // Simplified - in production use proper secp256k1 library
        // This is a placeholder showing the concept
        byte[] result = new byte[32];
        System.arraycopy(publicKey, 1, result, 0, 32);
        
        // In reality, this would be: public_key + tweak * G on secp256k1 curve
        // Use bitcoinj or libsecp256k1 for proper implementation
        for (int i = 0; i < 32; i++) {
            result[i] ^= tweak[i];
        }
        
        return result;
    }

    private static byte[] compressPublicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Not an uncompressed public key");
        }
        
        byte[] compressed = new byte[33];
        byte y = uncompressed[64];
        compressed[0] = (byte) (y % 2 == 0 ? 0x02 : 0x03);
        System.arraycopy(uncompressed, 1, compressed, 1, 32);
        return compressed;
    }
}