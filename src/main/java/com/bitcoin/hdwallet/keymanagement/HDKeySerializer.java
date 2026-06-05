package com.bitcoin.hdwallet.keymanagement;

/**
 *
 * @author CONALDES
 */

import java.security.*;
import java.security.spec.*;

/**
 * Converts ECKey's PrivateKey and PublicKey to/from raw bytes for DB storage.
 */
public class HDKeySerializer {

    /**
     * Serialize PrivateKey to bytes (PKCS#8 DER format).
     * @param privateKey
     * @return 
     */
    public static byte[] serializePrivateKey(PrivateKey privateKey) {
        return privateKey.getEncoded(); // PKCS#8
    }

    /**
     * Deserialize PrivateKey from PKCS#8 bytes.
     * @param bytes
     * @return 
     */
    public static PrivateKey deserializePrivateKey(byte[] bytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to deserialize private key", e);
        }
    }

    /**
     * Serialize PublicKey to bytes (X.509 DER format).
     * @param publicKey
     * @return 
     */
    public static byte[] serializePublicKey(PublicKey publicKey) {
        return publicKey.getEncoded(); // X.509
    }

    /**
     * Deserialize PublicKey from X.509 bytes.
     * @param bytes
     * @return 
     */
    public static PublicKey deserializePublicKey(byte[] bytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to deserialize public key", e);
        }
    }
}