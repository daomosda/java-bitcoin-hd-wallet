package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;

public class StringUtils {
    // Applies Sha256 to a string and returns the result.    
   
    // Add this static block to your StringUtils class to register the Bouncy Castle provider
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Derives the public key from a given EC private key using the Bouncy Castle provider.
     * This method is compatible with Java 8.
     *
     * @param privateKey The private key to derive the public key from.
     * @return The corresponding PublicKey.
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     * @throws NoSuchProviderException
     */
    public static PublicKey getPublicKeyFromPrivate(PrivateKey privateKey) throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchProviderException {
        // 1. Cast the private key to a Bouncy Castle specific implementation
        BCECPrivateKey bcPrivateKey = (BCECPrivateKey) privateKey;

        // 2. Get the elliptic curve parameters from the private key
        ECParameterSpec ecSpec = bcPrivateKey.getParameters();

        // 3. Get the generator point 'G' and the private value 's'
        ECPoint G = ecSpec.getG();
        java.math.BigInteger s = bcPrivateKey.getD();

        // 4. Calculate the public point 'W' by multiplying the generator point 'G' by the private value 's'
        // W = s * G. Bouncy Castle's ECPoint class has the multiply() method.
        ECPoint W = G.multiply(s);

        // 5. Create a public key specification from the public point 'W'
        ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(W, ecSpec);

        // 6. Generate the public key from the specification using the Bouncy Castle provider
        KeyFactory keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        return keyFactory.generatePublic(publicKeySpec);
    }

    public static byte[] applySha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static String applySha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    } 
    
    public static byte[] doubleSha256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] first = sha256.digest(data);
            return sha256.digest(first);          // second SHA-256 [web:523][web:528][web:530]
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // Returns a string from a Key
    public static String getStringFromKey(Key key) {
        if (key == null) {
            throw new IllegalArgumentException("PublicKey is null");
        }
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
   
    // Applies ECDSA Signature and returns a byte array
    /*
    public static byte[] applyECDSASig(PrivateKey privateKey, String input) {
        try {
            Signature dsa = Signature.getInstance("SHA256withECDSA");
            dsa.initSign(privateKey);
            byte[] strByte = input.getBytes();
            dsa.update(strByte);
            return dsa.sign();
        } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
            throw new RuntimeException(e);
        }
    }
    */
    
    public static byte[] applyECDSASig(PrivateKey privateKey, byte[] message) {
        try {
            // If 'message' is already SHA-256, use NONEwithECDSA; otherwise SHA256withECDSA
            Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
            dsa.initSign(privateKey);
            dsa.update(message);
            return dsa.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    // Verifies a String signature
    public static boolean verifyECDSASig(PublicKey publicKey, String data, byte[] signature) {
        try {
            Signature ecdsaVerify = Signature.getInstance("SHA256withECDSA");
            ecdsaVerify.initVerify(publicKey);
            ecdsaVerify.update(data.getBytes());
            return ecdsaVerify.verify(signature);
        } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
            throw new RuntimeException(e);
        }
    }

    // Add to StringUtils.java
    public static PrivateKey getPrivateKeyFromString(String keyStr) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }

    public static PublicKey getPublicKeyFromString(String keyStr) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
    
    // Add this method to your StringUtils.java class
    public static String getStringFromBytes(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    public static PublicKey getPublicKeyFromBytes(byte[] bytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");
            return keyFactory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to reconstruct public key", e);
        }
    }
    
    /**
     * Compute SHA-256 hash of the input bytes and return the 32-byte digest.
     */
    public static byte[] applySha256Bytes(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input); // 32 bytes
        } catch (NoSuchAlgorithmException e) {
            // Should never happen on a standard JVM
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String applySha256Hex(byte[] input) {
        byte[] hash = applySha256Bytes(input);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }    
    
    public static byte[] applyRIPEMD160(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("RIPEMD160");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static String getDifficultyString(int difficulty) {
        return "0".repeat(Math.max(0, difficulty));
    }
    
    public static PrivateKey secp256k1PrivateKeyFromBigInt(BigInteger d)
            throws GeneralSecurityException {

        if (d == null) {
            throw new IllegalArgumentException("d (private scalar) cannot be null");
        }

        // Ensure BouncyCastle is registered once in your app startup:
        // Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        ECNamedCurveParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECPrivateKeySpec privSpec = new ECPrivateKeySpec(d, bcSpec);

        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        return kf.generatePrivate(privSpec);
    }
    
    /**
     * Convert PKCS#8-encoded private key bytes to a PrivateKey.
     *
     * @param privateKeyBytes bytes from PrivateKey.getEncoded()
     * @return 
     */
    public static PrivateKey privateKeyFromPkcs8Bytes(byte[] privateKeyBytes) {
        try {
            KeyFactory kf = KeyFactory.getInstance("EC"); // or "EC","BC" if using BouncyCastle
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKeyBytes);
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild PrivateKey from PKCS#8 bytes", e);
        }
    }
    
    
    /**
     * Convert a raw 32-byte EC private scalar (secp256k1) to a PrivateKey.
     */
    public static PrivateKey privateKeyFromRawScalar(byte[] privScalar32) {
        if (privScalar32 == null || privScalar32.length != 32) {
            throw new IllegalArgumentException("privScalar32 must be 32 bytes");
        }

        try {
            // 1. Build BigInteger from scalar
            BigInteger d = new BigInteger(1, privScalar32);

            // 2. Get secp256k1 parameters (using BC or your provider that supports it)
            // If using pure JDK without BC, you'll need custom ECParameterSpec
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256k1"));
            ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

            // 3. Create key spec and generate key
            ECPrivateKeySpec privSpec = new ECPrivateKeySpec(d, ecSpec);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(privSpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidParameterSpecException e) {
            throw new RuntimeException("Failed to rebuild PrivateKey from raw scalar", e);
        }
    }
} 

