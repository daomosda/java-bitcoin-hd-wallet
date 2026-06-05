package com.bitcoin.hdwallet.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
/**
 *
 * @author CONALDES
 */

// Base58.java
public final class Base58 {

    //private static final Logger log = LoggerFactory.getLogger(Base58.class);

    private Base58() {}

    private static final char[] ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private static final int[] INDEXES = new int[128];
    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    // ---------------------------------------------------------------
    // Core encode / decode
    // ---------------------------------------------------------------

    /**
     * Encodes raw bytes to a Base58 string (no checksum).
     */
    public static String encode(byte[] input) {
        if (input.length == 0) return "";

        // Count leading zero bytes → leading '1' characters
        int leadingZeros = 0;
        for (byte b : input) {
            if (b == 0) leadingZeros++;
            else break;
        }

        // Convert bytes to a big-endian big integer via repeated divmod 58
        byte[] copy = Arrays.copyOf(input, input.length);
        char[] temp = new char[copy.length * 2]; // worst-case output length
        int    pos  = temp.length;

        int start = leadingZeros;
        while (start < copy.length) {
            int remainder = 0;
            for (int i = start; i < copy.length; i++) {
                int digit = (copy[i] & 0xff) + (remainder * 256);
                copy[i]   = (byte)(digit / 58);
                remainder  = digit % 58;
                if (copy[i] == 0 && i == start) start++;
            }
            temp[--pos] = ALPHABET[remainder];
        }

        // Add '1' for each leading zero byte
        for (int i = 0; i < leadingZeros; i++) {
            temp[--pos] = '1';
        }

        return new String(temp, pos, temp.length - pos);
    }

    /**
     * Decodes a Base58 string to raw bytes (no checksum verification).
     *
     * @throws IllegalArgumentException on invalid characters
     */
    public static byte[] decode(String input) {
        if (input.isEmpty()) return new byte[0];

        // Convert Base58 characters to byte values
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int  d = (c < 128) ? INDEXES[c] : -1;
            if (d < 0) {
                throw new IllegalArgumentException(
                    "Invalid Base58 character '" + c + "' at position " + i);
            }
            input58[i] = (byte) d;
        }

        // Count leading '1' characters → leading zero bytes
        int leadingZeros = 0;
        for (byte b : input58) {
            if (b == 0) leadingZeros++;
            else break;
        }

        // Convert from base 58 back to bytes
        byte[] output = new byte[input.length()];
        int    pos    = output.length;

        int start = leadingZeros;
        while (start < input58.length) {
            int remainder = 0;
            for (int i = start; i < input58.length; i++) {
                int digit  = (input58[i] & 0xff) + (remainder * 58);
                input58[i] = (byte)(digit / 256);
                remainder  = digit % 256;
                if (input58[i] == 0 && i == start) start++;
            }
            output[--pos] = (byte) remainder;
        }

        // Strip extra leading zero bytes that resulted from the conversion
        while (pos < output.length && output[pos] == 0) pos++;

        // Re-add the leading zeros that correspond to leading '1' characters
        return Arrays.copyOfRange(output, pos - leadingZeros, output.length);
    }

    // ---------------------------------------------------------------
    // Checked variants (4-byte SHA256d checksum appended)
    // ---------------------------------------------------------------

    /**
     * Encodes bytes with a 4-byte checksum appended (Base58Check).
     * Used for Bitcoin addresses and WIF private keys.
     */
    public static String encodeChecked(byte[] payload) {
        byte[] checksum = checksum(payload);
        byte[] full     = new byte[payload.length + 4];
        System.arraycopy(payload,  0, full, 0,               payload.length);
        System.arraycopy(checksum, 0, full, payload.length,  4);
        return encode(full);
    }

    /**
     * Convenience: prepend a version byte then encode with checksum.
     * e.g. encodeChecked(0x00, hash160bytes) → P2PKH mainnet address
     */
    public static String encodeChecked(int version, byte[] payload) {
        byte[] versioned = new byte[1 + payload.length];
        versioned[0] = (byte)(version & 0xff);
        System.arraycopy(payload, 0, versioned, 1, payload.length);
        return encodeChecked(versioned);
    }

    /**
     * Decodes a Base58Check string, verifies the checksum, and returns
     * the raw payload (without the version byte or checksum).
     *
     * @throws IllegalArgumentException if the checksum is invalid
     */
    public static byte[] decodeChecked(String input) {
        byte[] full = decode(input);
        if (full.length < 5) {
            throw new IllegalArgumentException(
                "Base58Check input too short: " + input);
        }

        // Split payload and checksum
        byte[] payload  = Arrays.copyOf(full, full.length - 4);
        byte[] checksum = Arrays.copyOfRange(full, full.length - 4, full.length);
        byte[] expected = checksum(payload);

        if (!Arrays.equals(checksum, expected)) {
            throw new IllegalArgumentException(
                "Base58Check checksum mismatch for: " + input);
        }
        return payload;
    }

    /**
     * Returns the raw payload bytes (strips version byte and checksum).
     * Useful when you know the version and just want the hash160.
     */
    public static byte[] decodePayload(String input) {
        byte[] withVersion = decodeChecked(input);       // still has version byte
        return Arrays.copyOfRange(withVersion, 1, withVersion.length);
    }

    /**
     * Returns the version byte of a Base58Check-encoded string.
     */
    public static int decodeVersion(String input) {
        byte[] withVersion = decodeChecked(input);
        return withVersion[0] & 0xff;
    }

    // ---------------------------------------------------------------
    // WIF (Wallet Import Format) helpers
    // ---------------------------------------------------------------

    /**
     * Encodes a 32-byte private key as WIF.
     *
     * @param privateKey  raw 32-byte private key
     * @param compressed  true if the corresponding public key is compressed
     * @param mainnet     true for mainnet (0x80), false for testnet (0xef)
     */
    public static String encodeWIF(byte[] privateKey, boolean compressed, boolean mainnet) {
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("Private key must be 32 bytes");
        }
        int    version = mainnet ? 0x80 : 0xef;
        byte[] payload = compressed
            ? appendByte(privateKey, (byte) 0x01)   // compressed flag
            : privateKey;
        return encodeChecked(version, payload);
    }

    /**
     * Decodes a WIF string back to a 32-byte private key.
     *
     * @return the 32-byte private key
     */
    public static byte[] decodeWIF(String wif) {
        byte[] full = decodeChecked(wif);    // includes version byte
        // full[0] = version, full[1..32] = key, full[33] = 0x01 if compressed
        int keyStart = 1;
        int keyEnd   = (full.length == 34 && full[33] == 0x01) ? 33 : full.length;
        return Arrays.copyOfRange(full, keyStart, keyEnd);
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /** SHA256(SHA256(data)), returns first 4 bytes as checksum. */
    private static byte[] checksum(byte[] data) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] h1 = sha.digest(data);
            byte[] h2 = sha.digest(h1);
            return Arrays.copyOf(h2, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static byte[] appendByte(byte[] array, byte b) {
        byte[] result = Arrays.copyOf(array, array.length + 1);
        result[array.length] = b;
        return result;
    }
}