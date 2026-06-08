package com.bitcoin.hdwallet.keymanagement;

/**
 *
 * @author DAOMOSDA
 */

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class HDKeyUtils {

    private HDKeyUtils() {}
    
    public static PrivateKey decodePrivateKeyFromBase64(String privBase64)
            throws Exception {
        byte[] bytes = Base64.getDecoder().decode(privBase64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);   // PKCS#8 [web:482][web:484]
        KeyFactory kf = KeyFactory.getInstance("EC");                // or "EC", provider "BC" if you used BouncyCastle
        return kf.generatePrivate(spec);
    }

    public static PublicKey decodePublicKeyFromBase64(String pubBase64)
            throws Exception {
        byte[] bytes = Base64.getDecoder().decode(pubBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);     // X.509/SPKI [web:479][web:486]
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePublic(spec);
    }
    
    public static PrivateKey bytesToPrivateKey(byte[] priv_bytes)
            throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(priv_bytes);   // PKCS#8 [web:482][web:484]
        KeyFactory kf = KeyFactory.getInstance("EC");                // or "EC", provider "BC" if you used BouncyCastle
        return kf.generatePrivate(spec);
    }

    public static PublicKey bytesToPublicKey(byte[] pub_bytes)
            throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(pub_bytes);     // X.509/SPKI [web:479][web:486]
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePublic(spec);
    }

    /**
     * Sign the given 32-byte hash with ECDSA using a private key in PKCS#8-encoded bytes.
     *
     * @param privKeyBytes PKCS#8-encoded EC private key
     * @param hash         32-byte message hash
     * @return DER-encoded ECDSA signature bytes
     */
    public static byte[] sign(byte[] privKeyBytes, byte[] hash) {
        try {
            if (hash == null || hash.length == 0) {
                throw new IllegalArgumentException("hash cannot be null or empty");
            }

            KeyFactory kf = KeyFactory.getInstance("EC"); // or "EC","BC" if using BouncyCastle + secp256k1
            PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privKeyBytes);
            PrivateKey privateKey = kf.generatePrivate(privSpec);

            Signature ecdsa = Signature.getInstance("NONEwithECDSA"); // hash already provided
            ecdsa.initSign(privateKey);
            ecdsa.update(hash);
            return ecdsa.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Error signing hash with ECDSA", e);
        }
    }
}
