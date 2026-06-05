package com.bitcoin.hdwallet.core;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.crypto.SegWitAddress;
import com.bitcoin.hdwallet.crypto.Base58;
import com.bitcoin.hdwallet.crypto.Crypto;
import com.bitcoin.hdwallet.keymanagement.MultiMasterManager;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.bitcoin.hdwallet.tranxcontrol.TxSigner;
import com.bitcoin.hdwallet.model.Psbt;

/**
 * Minimal BIP-32 HD wallet for secp256k1, supporting testnet/mainnet and xprv/xpub serialization.
 */
public final class Bip32HDWallet {

    private static final BigInteger CURVE_N = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private static final org.bouncycastle.math.ec.ECCurve CURVE =
            org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").getCurve();

    private static final ECPoint G =
            org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").getG();

    private final byte[] chainCode;
    private final byte[] key; // private (32 bytes) or public (33 bytes)
    private final boolean isPublic;
    private final int depth;
    private final int fingerprint;
    private final int childNumber;
    //private final boolean testnet;
    private SegWitAddress.Network network = SegWitAddress.Network.REGTEST;
    
    private final boolean testnet;

    private Bip32HDWallet(byte[] chainCode, byte[] key, boolean isPublic,
                          int depth, int fingerprint, int childNumber, boolean testnet) {
        this.chainCode = chainCode;
        this.key = key;
        this.isPublic = isPublic;
        this.depth = depth;
        this.fingerprint = fingerprint;
        this.childNumber = childNumber;
        this.testnet = testnet;
    }

    // -------- Factory: master from BIP39 seed --------
    public static Bip32HDWallet fromSeed(byte[] seed, boolean testnet) {
        byte[] I = hmacSha512("Bitcoin seed".getBytes(), seed);
        byte[] IL = Arrays.copyOfRange(I, 0, 32);
        byte[] IR = Arrays.copyOfRange(I, 32, 64);

        BigInteger k = new BigInteger(1, IL).mod(CURVE_N);
        if (k.signum() == 0) {
            throw new IllegalStateException("Generated zero master key");
        }

        return new Bip32HDWallet(
                IR,
                to32Bytes(k),
                false,
                0,
                0,
                0,
                testnet
        );
    }

    // -------- Derivation --------

    public Bip32HDWallet deriveChild(int index) {
        boolean hardened = (index & 0x80000000) != 0;

        if (hardened && isPublic) {
            throw new IllegalArgumentException("Cannot derive hardened child from public key");
        }

        byte[] data;
        if (hardened) {
            data = new byte[1 + 32 + 4];
            data[0] = 0x00;
            System.arraycopy(key, 0, data, 1, 32); // private key
        } else {
            byte[] pub = getPublicKeyCompressed();
            data = new byte[pub.length + 4];
            System.arraycopy(pub, 0, data, 0, pub.length);
        }
        // append index
        data[data.length - 4] = (byte) ((index >>> 24) & 0xFF);
        data[data.length - 3] = (byte) ((index >>> 16) & 0xFF);
        data[data.length - 2] = (byte) ((index >>> 8) & 0xFF);
        data[data.length - 1] = (byte) (index & 0xFF);

        byte[] I = hmacSha512(chainCode, data);
        byte[] IL = Arrays.copyOfRange(I, 0, 32);
        byte[] IR = Arrays.copyOfRange(I, 32, 64);

        BigInteger Il = new BigInteger(1, IL);
        if (Il.compareTo(CURVE_N) >= 0) {
            throw new IllegalStateException("IL >= n, invalid child");
        }

        byte[] childKey;
        boolean childIsPublic = isPublic;

        if (!isPublic) {
            BigInteger kpar = new BigInteger(1, key);
            BigInteger ki = Il.add(kpar).mod(CURVE_N);
            if (ki.signum() == 0) {
                throw new IllegalStateException("Derived zero child key");
            }
            childKey = to32Bytes(ki);
        } else {
            ECPoint Kpar = CURVE.decodePoint(key);
            ECPoint Ki = G.multiply(Il).add(Kpar).normalize();
            childKey = Ki.getEncoded(true);
            childIsPublic = true;
        }

        int fingerprint = this.getFingerprintValue();
        int depth = this.depth + 1;

        return new Bip32HDWallet(
                IR,
                childKey,
                childIsPublic,
                depth,
                fingerprint,
                index,
                testnet
        );
    }

    /** Derive a path like "m/84'/1'/0'/0/5".
     * @param path
     * @return  */
    public Bip32HDWallet derivePath(String path) {
        if (!path.startsWith("m")) {
            throw new IllegalArgumentException("Path must start with m");
        }
        Bip32HDWallet node = this;
        if ("m".equals(path)) return node;

        String[] components = path.split("/");
        for (int i = 1; i < components.length; i++) {
            String c = components[i].trim();
            boolean hardened = c.endsWith("'") || c.endsWith("h");
            if (hardened) {
                c = c.substring(0, c.length() - 1);
            }
            int idx = Integer.parseInt(c);
            if (idx < 0) throw new IllegalArgumentException("Negative index in path");
            if (hardened) {
                idx |= 0x80000000;
            }
            node = node.deriveChild(idx);
        }
        return node;
    }

    // -------- Public / Private key access --------

    public byte[] getPrivateKeyBytes() {
        if (isPublic) {
            throw new IllegalStateException("No private key in this node");
        }
        return Arrays.copyOf(key, key.length);
    }

    public byte[] getPublicKeyCompressed() {
        if (isPublic) {
            return Arrays.copyOf(key, key.length);
        }
        BigInteger k = new BigInteger(1, key);
        ECPoint Q = G.multiply(k).normalize();
        return Q.getEncoded(true);
    }
       
    public String signPsbt(String psbtBase64) throws Exception {
        Psbt psbt = Psbt.fromBase64(psbtBase64);
        Bip32HDWallet masterKey = (Bip32HDWallet) CachedWalletMnemMap.getObject("masterKey");

        List<Psbt.PsbtInput> inputs = psbt.getInputs();
        for (int inIndex = 0; inIndex < inputs.size(); inIndex++) {
            Psbt.PsbtInput in = inputs.get(inIndex);

            Psbt.Bip32Derivation deriv = pickOurDerivation(in);
            
            if (deriv == null) {
                System.out.println("No derivation for input " + inIndex);
                continue;
            }

            System.out.println("Input " + inIndex + " fingerprint: " + deriv.getFingerprint());
            System.out.println("Input " + inIndex + " raw path: " + deriv.getPath());
            String pathStr = pathToString(deriv.getPath());
            System.out.println("Input " + inIndex + " pathStr: " + pathStr);            
            
            //if (deriv == null) continue;

            //String pathStr = pathToString(deriv.getPath());
            Bip32HDWallet child = masterKey.derivePath(pathStr);
            byte[] privKey = child.getPrivateKeyBytes();

            byte[] sighash = TxSigner.computeSegwitV0Sighash(psbt, inIndex);
            byte[] sig = TxSigner.signSchnorrOrEcdsa(privKey, sighash);

            psbt.addSignatureToInput(inIndex, child.getPublicKeyCompressed(), sig);
        }

        return psbt.toBase64();
    }
    
    public String signPsbt(String psbtBase64, MultiMasterManager masters) throws Exception {
        Psbt psbt = Psbt.fromBase64(psbtBase64);

        List<Psbt.PsbtInput> inputs = psbt.getInputs();
        for (int inIndex = 0; inIndex < inputs.size(); inIndex++) {
            Psbt.PsbtInput in = inputs.get(inIndex);

            // 1. pick fingerprint + derivation
            Psbt.Bip32Derivation deriv = null;
            int inputFp = 0;
            for (Map.Entry<Psbt.ByteArrayWrapper, Psbt.Bip32Derivation> e :
                    in.getBip32Derivations().entrySet()) {
                Psbt.Bip32Derivation d = e.getValue();
                inputFp = d.getFingerprint();
                deriv = d;
                break; // if you support multisig, refine this
            }

            if (deriv == null) {
                System.out.println("No bip32_derivs for input " + inIndex);
                continue;
            }

            Bip32HDWallet master = masters.findMasterByFingerprint(inputFp);
            if (master == null) {
                System.out.printf("No master key for fingerprint %08x (input %d)%n", inputFp, inIndex);
                continue;
            }

            List<Integer> path = deriv.getPath();
            String pathStr = pathToString(path);
            Bip32HDWallet child = master.derivePath(pathStr);
            byte[] privKey = child.getPrivateKeyBytes();

            byte[] sighash = TxSigner.computeSegwitV0Sighash(psbt, inIndex);
            byte[] sig = TxSigner.signSchnorrOrEcdsa(privKey, sighash);

            psbt.addSignatureToInput(inIndex, child.getPublicKeyCompressed(), sig);
        }

        return psbt.toBase64();
    }
      
    private String pathToString(List<Integer> path) {
        StringBuilder sb = new StringBuilder("m");
        for (int idx : path) {
            boolean hardened = (idx & 0x80000000) != 0;
            int index = idx & 0x7FFFFFFF;
            sb.append('/');
            sb.append(index);
            if (hardened) {
                sb.append('\'');   // single quote
            }
        }
        return sb.toString();
    }
    
    private Psbt.Bip32Derivation pickOurDerivation(Psbt.PsbtInput in) {
        Bip32HDWallet masterKey = (Bip32HDWallet) CachedWalletMnemMap.getObject("masterKey");
        int masterFp = masterKey.getFingerprintValue();

        System.out.printf("Master fingerprint: %08x%n", masterFp);
        for (var e : in.getBip32Derivations().entrySet()) {
            Psbt.Bip32Derivation deriv = e.getValue();
            System.out.printf("Input deriv fingerprint: %08x%n", deriv.getFingerprint());
            if (deriv.getFingerprint() == masterFp) {
                return deriv;
            }
        }
        
        //for (Map.Entry<Psbt.ByteArrayWrapper, Psbt.Bip32Derivation> e :
        //        in.getBip32Derivations().entrySet()) { 
        //    Psbt.Bip32Derivation deriv = e.getValue();
        //    if (deriv.getFingerprint() == masterFp) {
        //        return deriv;
        //    }
        //}
        // If you use account-level keys with different fingerprints, you can
        // check against those instead or in addition.
        return null;
    }
       
    public int getFingerprintValue() {
        byte[] pub = getPublicKeyCompressed();
        byte[] hash160 = Crypto.hash160(pub); // RIPEMD160(SHA256(pub))

        // first 4 bytes big-endian
        return ((hash160[0] & 0xFF) << 24) |
               ((hash160[1] & 0xFF) << 16) |
               ((hash160[2] & 0xFF) << 8)  |
                (hash160[3] & 0xFF);
    }
    
    public String getFingerprintHex() {

        return String.format("%08x", getFingerprintValue());
    }
    
    public static Bip32HDWallet fromBase58(String base58) {
        // Decode full payload + checksum (expected length: 82 bytes)
        byte[] extended = Base58.decode(base58);

        if (extended.length != 82) {
            throw new IllegalArgumentException("Invalid extended key length: " + extended.length);
        }

        // Split into 78-byte payload and 4-byte checksum
        byte[] serialized = Arrays.copyOfRange(extended, 0, 78);
        byte[] checksum = Arrays.copyOfRange(extended, 78, 82);

        // Verify checksum: SHA256d(serialized)[0..4)
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] first = sha256.digest(serialized);
            byte[] second = sha256.digest(first);
            byte[] expected = Arrays.copyOfRange(second, 0, 4);
            if (!Arrays.equals(expected, checksum)) {
                throw new IllegalArgumentException("Invalid extended key checksum");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // Parse the 78-byte payload
        int version =
                ((serialized[0] & 0xFF) << 24) |
                ((serialized[1] & 0xFF) << 16) |
                ((serialized[2] & 0xFF) <<  8) |
                 (serialized[3] & 0xFF);

        boolean isPrivate;
        boolean testnet;

        switch (version) {
            case 0x0488ADE4: // xprv mainnet
                isPrivate = true;
                testnet = false;
                break;
            case 0x04358394: // tprv testnet
                isPrivate = true;
                testnet = true;
                break;
            case 0x0488B21E: // xpub mainnet
                isPrivate = false;
                testnet = false;
                break;
            case 0x043587CF: // tpub testnet
                isPrivate = false;
                testnet = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown extended key version: " + Integer.toHexString(version));
        }

        int depth = serialized[4] & 0xFF;

        int parentFp =
                ((serialized[5] & 0xFF) << 24) |
                ((serialized[6] & 0xFF) << 16) |
                ((serialized[7] & 0xFF) <<  8) |
                 (serialized[8] & 0xFF);

        int childNumber =
                ((serialized[9]  & 0xFF) << 24) |
                ((serialized[10] & 0xFF) << 16) |
                ((serialized[11] & 0xFF) <<  8) |
                 (serialized[12] & 0xFF);

        byte[] chainCode = Arrays.copyOfRange(serialized, 13, 45);
        byte[] keyData   = Arrays.copyOfRange(serialized, 45, 78);

        byte[] privKey = null;
        boolean publicOnly;

        if (isPrivate) {
            if (keyData[0] != 0x00) {
                throw new IllegalArgumentException("Invalid private key marker");
            }
            privKey = Arrays.copyOfRange(keyData, 1, 33); // last 32 bytes
            publicOnly = false;
        } else {
            // Public-only; keyData is compressed pubkey
            privKey = null;
            publicOnly = true;
        }

        return new Bip32HDWallet(
                chainCode,
                privKey,
                publicOnly,
                depth,
                parentFp,
                childNumber,
                testnet
        );
    }

    public String toBase58(boolean testnet, boolean publicOnly) {
        try {
            int version;
            if (publicOnly || isPublic) {
                version = testnet ? 0x043587CF : 0x0488B21E; // tpub/xpub
            } else {
                version = testnet ? 0x04358394 : 0x0488ADE4; // tprv/xprv
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            // 4 bytes: version
            bos.write(new byte[]{
                    (byte) ((version >>> 24) & 0xFF),
                    (byte) ((version >>> 16) & 0xFF),
                    (byte) ((version >>> 8) & 0xFF),
                    (byte) (version & 0xFF)
            });

            // 1 byte: depth
            bos.write((byte) depth);

            // 4 bytes: parent fingerprint
            bos.write(new byte[]{
                    (byte) ((fingerprint >>> 24) & 0xFF),
                    (byte) ((fingerprint >>> 16) & 0xFF),
                    (byte) ((fingerprint >>> 8) & 0xFF),
                    (byte) (fingerprint & 0xFF)
            });

            // 4 bytes: child number
            bos.write(new byte[]{
                    (byte) ((childNumber >>> 24) & 0xFF),
                    (byte) ((childNumber >>> 16) & 0xFF),
                    (byte) ((childNumber >>> 8) & 0xFF),
                    (byte) (childNumber & 0xFF)
            });

            // 32 bytes: chain code
            bos.write(chainCode);

            // 33 bytes: key data
            if (publicOnly || isPublic) {
                byte[] pub = getPublicKeyCompressed();
                if (pub.length != 33) {
                    throw new IllegalStateException("Pubkey must be 33 bytes");
                }
                bos.write(pub);
            } else {
                if (key.length != 32) {
                    throw new IllegalStateException("Privkey must be 32 bytes");
                }
                bos.write(0x00);
                bos.write(key);
            }

            byte[] serialized = bos.toByteArray();

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] first = sha256.digest(serialized);
            byte[] second = sha256.digest(first);
            byte[] checksum = Arrays.copyOfRange(second, 0, 4);

            byte[] extended = new byte[serialized.length + 4];
            System.arraycopy(serialized, 0, extended, 0, serialized.length);
            System.arraycopy(checksum, 0, extended, serialized.length, 4);

            return Base58.encode(extended);

        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to serialize extended key", e);
        }
    }

    public String toBase58PubTestnet() {
        return toBase58(true, true);
    }

    public String toBase58PrvTestnet() {
        return toBase58(true, false);
    }

    public String toBase58PubMainnet() {
        return toBase58(false, true);
    }

    public String toBase58PrvMainnet() {
        return toBase58(false, false);
    }

    // -------- Convenience: account xpub --------

    public String getAccountXpub(int purpose, int coinType, int accountIndex) {
        String path = String.format("m/%d'/%d'/%d'", purpose, coinType, accountIndex);
        Bip32HDWallet accountNode = this.derivePath(path);
        return accountNode.toBase58(this.testnet, true);
    }
    
    // -------- Helpers --------

    private static byte[] hmacSha512(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] out = new byte[64];
        hmac.doFinal(out, 0);
        return out;
    }

    private static byte[] to32Bytes(BigInteger v) {
        byte[] tmp = v.toByteArray();
        if (tmp.length == 32) return tmp;
        byte[] res = new byte[32];
        if (tmp.length > 32) {
            System.arraycopy(tmp, tmp.length - 32, res, 0, 32);
        } else {
            System.arraycopy(tmp, 0, res, 32 - tmp.length, tmp.length);
        }
        return res;
    }
}