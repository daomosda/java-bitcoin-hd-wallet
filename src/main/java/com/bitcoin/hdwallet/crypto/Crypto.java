package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.chainindexing.KeyPair;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;

import org.bouncycastle.math.ec.ECPoint;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public final class Crypto {
    
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    public static byte[] sha256(byte[]... data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : data) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }    
    
    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
        
    public static byte[] random32() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }
    
    //################################################################################################
    /**
     * Generates n cryptographically secure random bytes.
     *
     * @param n number of bytes to generate
     * @return  fresh n-byte array of random data
     */
    public static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Generates a random 32-byte value that is also a valid secp256k1
     * private key scalar (1 ≤ k < n).
     *
     * Used when the random bytes will be used directly as a private key
     * rather than just entropy.
     *
     * @return valid 32-byte secp256k1 private key
     */
    public static byte[] randomPrivateKey() {
        org.bouncycastle.asn1.x9.X9ECParameters params =
            org.bouncycastle.crypto.ec.CustomNamedCurves
                .getByName("secp256k1");
        org.bouncycastle.math.ec.ECCurve curve = params.getCurve();
        java.math.BigInteger n = params.getN();

        java.math.BigInteger key;
        byte[]              bytes;
        do {
            bytes = new byte[32];
            SECURE_RANDOM.nextBytes(bytes);
            key   = new java.math.BigInteger(1, bytes);
        } while (key.compareTo(java.math.BigInteger.ONE) < 0
               || key.compareTo(n) >= 0);

        return to32Bytes(key);
    }

    // ── SHA-256 ───────────────────────────────────────────────────────
       
    public static byte[] sha256(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Crypto.sha256: input string must not be null");
        }
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256d(byte[] data) {
        return sha256(sha256(data));
    }
    
    public static byte[] ripemd160(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("RIPEMD160");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RIPEMD160 not available. Did you add BouncyCastle?", e);
        }
    }

    /** HASH160 = RIPEMD160(SHA256(data))
     * @param data
     * @return  */
    public static byte[] hash160(byte[] data) {
        return ripemd160(sha256(data));
    }
    /**
     * Bitcoin-style double SHA-256: SHA256(SHA256(data)).
     */
    public static byte[] doubleSha256(byte[] data) {
        return sha256(sha256(data));
    }
    //################################################################################################  
    
    public static byte[] sign(byte[] data, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
            throw new RuntimeException("Signing failed", e);
        }
    }

    public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
            throw new RuntimeException("Verification failed", e);
        }
    }

    public static byte[] generatePreimage() {
        byte[] preimage = new byte[32];
        new java.security.SecureRandom().nextBytes(preimage);
        return preimage;
    }
    
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");

        ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");

        keyGen.initialize(ecSpec, new SecureRandom());

        java.security.KeyPair kp = keyGen.generateKeyPair();

        byte[] priv = ((BCECPrivateKey) kp.getPrivate()).getD().toByteArray();
        byte[] pub  = ((BCECPublicKey) kp.getPublic()).getEncoded();   //.getQ().getEncoded(); // compressed

        return new KeyPair(priv, pub);
    }
    
    public static byte[] ecdh(byte[] privKeyBytes, byte[] pubKeyBytes) throws Exception {

        ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");

        BigInteger priv = new BigInteger(1, privKeyBytes);

        ECPoint pubPoint = ecSpec.getCurve().decodePoint(pubKeyBytes);

        ECPoint sharedPoint = pubPoint.multiply(priv);

        byte[] sharedX = sharedPoint.getEncoded(true);

        return sha256(sharedX); // Lightning uses hashed shared secret
    }
    
    public static byte[] blindPrivKey(byte[] privKey, byte[] blindFactor) {

        BigInteger priv = new BigInteger(1, privKey);
        BigInteger blind = new BigInteger(1, blindFactor);

        ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
        BigInteger n = ecSpec.getN();

        BigInteger newPriv = priv.multiply(blind).mod(n);

        return to32Bytes(newPriv);
    }
        
    public static byte[] blindPubKey(byte[] pubKey, byte[] blindFactor) {

        ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");

        ECPoint pubPoint = ecSpec.getCurve().decodePoint(pubKey);

        BigInteger blind = new BigInteger(1, blindFactor);

        ECPoint newPoint = pubPoint.multiply(blind);

        return newPoint.getEncoded(true);
    }      
    
    public static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");

        mac.init(keySpec);

        return mac.doFinal(data);
    }    
    
    public static byte[] to32Bytes(BigInteger value) {
        byte[] bytes = value.toByteArray();

        if (bytes.length == 32) return bytes;

        byte[] result = new byte[32];

        if (bytes.length > 32) {
            System.arraycopy(bytes, bytes.length - 32, result, 0, 32);
        } else {
            System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);
        }

        return result;
    }    
}
