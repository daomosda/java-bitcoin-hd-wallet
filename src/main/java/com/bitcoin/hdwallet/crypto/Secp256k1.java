package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

// ── Secp256k1.java ────────────────────────────────────────────────────

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import org.bouncycastle.asn1.sec.SECNamedCurves;

/**
 * secp256k1 elliptic curve operations.
 *
 * Secp256k1.ecdh() — Elliptic Curve Diffie-Hellman shared secret.
 *
 * Used by OnionBuilder to derive per-hop shared secrets:
 *   sharedSecret = ECDH(ephemeralPrivKey, recipientPubKey)
 *
 * Bitcoin/Lightning standard:
 *   sharedSecret = SHA-256( ephemeralPrivKey * recipientPubKey )
 *   where * is EC scalar multiplication (point multiplication).
 */
public final class Secp256k1 {

    //private Secp256k1() {}
    
    private static final Secp256k1 INSTANCE =
        new Secp256k1();

    private final X9ECParameters params;

    private Secp256k1() {
        params =
            SECNamedCurves.getByName("secp256k1");
    }

    public static Secp256k1 get() {
        return INSTANCE;
    }

    public ECPoint getG() {
        return params.getG();
    }

    public BigInteger getN() {
        return params.getN();
    }

    public X9ECParameters getParams() {
        return params;
    }

    // ── Curve parameters (loaded once) ───────────────────────────────

    private static final X9ECParameters CURVE_PARAMS =
        CustomNamedCurves.getByName("secp256k1");

    /** The elliptic curve itself. */
    public static final org.bouncycastle.math.ec.ECCurve CURVE =
        CURVE_PARAMS.getCurve();

    /** Generator point G. */
    public static final ECPoint G = CURVE_PARAMS.getG();

    /** Curve order n. */
    public static final BigInteger N = CURVE_PARAMS.getN();
    
    
    // ── ECDH ──────────────────────────────────────────────────────────

    /**
     * Computes the ECDH shared secret between a private key and a public key.
     *
     * Formula (Lightning / Bitcoin standard):
     *   sharedSecret = SHA-256( privKey * pubKey )
     *
     * Where:
     *   privKey * pubKey  = EC point multiplication
     *   SHA-256(point)    = hash of the compressed point (33 bytes)
     *
     * Step by step:
     *   1. Decode the 33-byte compressed pubKey into an ECPoint.
     *   2. Multiply the point by the privKey scalar → new ECPoint.
     *   3. Serialize the resulting point in compressed form (33 bytes).
     *   4. SHA-256 hash the compressed point → 32-byte shared secret.
     *
     * Why SHA-256?
     *   Raw ECDH gives a point, not a uniform byte string.
     *   Hashing maps the point to a uniform 32-byte key suitable for
     *   use as a symmetric encryption key (ChaCha20, AES, HKDF input).
     *
     * @param  privKeyBytes  32-byte private key scalar (e.g. from Crypto.random32())
     * @param  pubKeyBytes   33-byte compressed secp256k1 public key
     * @return               32-byte shared secret
     *
     * @throws IllegalArgumentException if either input is invalid
     */
    public static byte[] ecdh(byte[] privKeyBytes, byte[] pubKeyBytes) {
        validatePrivKey(privKeyBytes);
        validatePubKey (pubKeyBytes);

        // 1. Decode compressed public key (0x02 or 0x03 prefix)
        ECPoint pubPoint = decodePoint(pubKeyBytes);

        // 2. Scalar multiplication: sharedPoint = privKey * pubKeyPoint
        BigInteger privScalar   = new BigInteger(1, privKeyBytes);
        ECPoint    sharedPoint  = pubPoint.multiply(privScalar).normalize();

        if (sharedPoint.isInfinity()) {
            throw new IllegalArgumentException(
                "ECDH produced point at infinity — invalid private key");
        }

        // 3. Serialize shared point in compressed form (33 bytes)
        byte[] compressed = sharedPoint.getEncoded(true);

        // 4. SHA-256(compressed) → 32-byte shared secret
        return sha256(compressed);
    }

    /**
     * Overload that accepts BigInteger private key directly.
     */
    public static byte[] ecdh(BigInteger privKey, byte[] pubKeyBytes) {
        return ecdh(Crypto.to32Bytes(privKey), pubKeyBytes);
    }

    // ── Public key derivation ─────────────────────────────────────────

    /**
     * Derives the compressed public key from a 32-byte private key.
     *
     * pubKey = privKey * G
     *
     * @param  privKeyBytes  32-byte private key
     * @return               33-byte compressed public key
     */
    public static byte[] privToPub(byte[] privKeyBytes) {
        validatePrivKey(privKeyBytes);
        BigInteger priv   = new BigInteger(1, privKeyBytes);
        ECPoint    pubPt  = G.multiply(priv).normalize();
        return pubPt.getEncoded(true);   // 33-byte compressed
    }

    /**
     * Derives the uncompressed public key (65 bytes, 0x04 prefix).
     */
    public static byte[] privToPubUncompressed(byte[] privKeyBytes) {
        validatePrivKey(privKeyBytes);
        BigInteger priv  = new BigInteger(1, privKeyBytes);
        ECPoint    pubPt = G.multiply(priv).normalize();
        return pubPt.getEncoded(false);  // 65-byte uncompressed
    }

    // ── Point operations ──────────────────────────────────────────────

    /**
     * Decodes a 33-byte compressed or 65-byte uncompressed public key.
     *
     * @param  encoded  compressed (0x02/0x03) or uncompressed (0x04) pubkey
     * @return          ECPoint on secp256k1
     */
    public static ECPoint decodePoint(byte[] encoded) {
        try {
            return CURVE.decodePoint(encoded).normalize();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid secp256k1 public key: " + bytesToHex(encoded), e);
        }
    }

    /**
     * Multiplies a point by a scalar.
     *
     *   result = scalar * point
     *
     * @param  scalar  32-byte big-endian integer
     * @param  point   compressed 33-byte ECPoint
     * @return         compressed 33-byte result point
     */
    public static byte[] pointMultiply(byte[] scalar, byte[] point) {
        BigInteger s = new BigInteger(1, scalar);
        ECPoint    p = decodePoint(point);
        return p.multiply(s).normalize().getEncoded(true);
    }

    /**
     * Adds two EC points.
     *
     *   result = a + b
     */
    public static byte[] pointAdd(byte[] pointA, byte[] pointB) {
        ECPoint a = decodePoint(pointA);
        ECPoint b = decodePoint(pointB);
        return a.add(b).normalize().getEncoded(true);
    }

    /**
     * Multiplies the generator G by a scalar.
     *
     *   result = scalar * G
     *
     * Used to derive public keys from private key scalars.
     */
    public static byte[] pointFromScalar(byte[] scalar) {
        BigInteger s = new BigInteger(1, scalar);
        return G.multiply(s).normalize().getEncoded(true);
    }

    // ── Validation ────────────────────────────────────────────────────

    /**
     * Validates a 32-byte private key: must be in range [1, n-1].
     */
    public static void validatePrivKey(byte[] privKey) {
        if (privKey == null || privKey.length != 32) {
            throw new IllegalArgumentException(
                "Private key must be exactly 32 bytes, got: "
                + (privKey == null ? "null" : privKey.length));
        }
        BigInteger scalar = new BigInteger(1, privKey);
        if (scalar.compareTo(BigInteger.ONE) < 0
                || scalar.compareTo(N) >= 0) {
            throw new IllegalArgumentException(
                "Private key scalar out of range [1, n-1]");
        }
    }

    /**
     * Validates a compressed (33 bytes) or uncompressed (65 bytes) pubkey.
     */
    public static void validatePubKey(byte[] pubKey) {
        if (pubKey == null) {
            throw new IllegalArgumentException("Public key must not be null");
        }
        if (pubKey.length != 33 && pubKey.length != 65) {
            throw new IllegalArgumentException(
                "Public key must be 33 (compressed) or 65 (uncompressed) bytes, got: "
                + pubKey.length);
        }
        byte prefix = pubKey[0];
        if (pubKey.length == 33 && prefix != 0x02 && prefix != 0x03) {
            throw new IllegalArgumentException(
                "Compressed public key must start with 0x02 or 0x03, got: "
                + String.format("0x%02x", prefix));
        }
        if (pubKey.length == 65 && prefix != 0x04) {
            throw new IllegalArgumentException(
                "Uncompressed public key must start with 0x04, got: "
                + String.format("0x%02x", prefix));
        }
        // verify the point is actually on the curve
        try {
            CURVE.decodePoint(pubKey);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Public key point is not on secp256k1 curve", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}