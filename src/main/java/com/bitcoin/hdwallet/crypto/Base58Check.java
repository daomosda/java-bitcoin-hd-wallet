package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */


import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class Base58Check {

    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final Map<Character, Integer> ALPHABET_MAP = new HashMap<>();
    static {
        for (int i = 0; i < ALPHABET.length; i++) {
            ALPHABET_MAP.put(ALPHABET[i], i);
        }
    }
    private static final BigInteger BASE = BigInteger.valueOf(58);

    // ... (existing base58Encode and base58CheckEncode methods remain the same) ...
    /**
     * Encodes a byte array into a Base58 string.
     * @param input The byte array to encode.
     * @return The Base58 encoded string.
     */
    public static String base58Encode(byte[] input) {
        BigInteger bi = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        while (bi.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] remainder = bi.divideAndRemainder(BASE);
            sb.append(ALPHABET[remainder[1].intValue()]);
            bi = remainder[0];
        }
        for (byte b : input) {
            if (b == 0) {
                sb.append(ALPHABET[0]);
            } else {
                break;
            }
        }
        return sb.reverse().toString();
    }

    /**
     * Performs Base58Check encoding on a payload with a given version prefix.
     * @param prefix The single-byte version prefix.
     * @param payload The data to encode.
     * @return The Base58Check encoded string.
     */
    public static String base58CheckEncode(byte prefix, byte[] payload) {
        try {
            byte[] versionedPayload = new byte[payload.length + 1];
            versionedPayload[0] = prefix;
            System.arraycopy(payload, 0, versionedPayload, 1, payload.length);

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash1 = sha256.digest(versionedPayload);
            byte[] hash2 = sha256.digest(hash1);
            byte[] checksum = new byte[4];
            System.arraycopy(hash2, 0, checksum, 0, 4);

            byte[] fullPayload = new byte[versionedPayload.length + 4];
            System.arraycopy(versionedPayload, 0, fullPayload, 0, versionedPayload.length);
            System.arraycopy(checksum, 0, fullPayload, versionedPayload.length, 4);

            return base58Encode(fullPayload);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Decodes a Base58Check encoded string.
     * @param input The Base58Check string to decode.
     * @return The decoded byte array (version + payload + checksum).
     * @throws IllegalArgumentException if the input is invalid.
     */
    public static byte[] base58CheckDecode(String input) throws IllegalArgumentException {
        // 1. Base58 decode to get the byte array
        byte[] decoded = base58Decode(input);

        if (decoded.length < 5) {
            throw new IllegalArgumentException("Input too short to be valid.");
        }

        // 2. Separate payload and checksum
        byte[] payload = new byte[decoded.length - 4];
        byte[] checksum = new byte[4];
        System.arraycopy(decoded, 0, payload, 0, payload.length);
        System.arraycopy(decoded, payload.length, checksum, 0, 4);

        // 3. Verify checksum
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash1 = sha256.digest(payload);
            byte[] hash2 = sha256.digest(hash1);
            byte[] calculatedChecksum = new byte[4];
            System.arraycopy(hash2, 0, calculatedChecksum, 0, 4);

            if (!MessageDigest.isEqual(checksum, calculatedChecksum)) {
                throw new IllegalArgumentException("Invalid checksum");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // 4. Return payload (including version byte)
        return payload;
    }

    /**
     * Decodes a Base58 string into a byte array.
     * @param input The Base58 string.
     * @return The decoded byte array.
     */
    public static byte[] base58Decode(String input) {
        // Count leading '1's
        int leadingZeros = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == ALPHABET[0]) {
                leadingZeros++;
            } else {
                break;
            }
        }

        // Convert the string to a BigInteger
        BigInteger num = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            if (!ALPHABET_MAP.containsKey(c)) {
                throw new IllegalArgumentException("Invalid character in Base58 string: " + c);
            }
            num = num.multiply(BASE).add(BigInteger.valueOf(ALPHABET_MAP.get(c)));
        }

        // Convert BigInteger to byte array
        byte[] bytes = num.toByteArray();

        // Remove sign byte if present
        if (bytes[0] == 0) {
            byte[] tmp = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, tmp, 0, tmp.length);
            bytes = tmp;
        }

        // Add leading zero bytes
        byte[] result = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, result, leadingZeros, bytes.length);
        return result;
    }   
    
    public static String encode(byte[] payload) {
        // 1. Double SHA256
        byte[] checksum = doubleSha256(payload);
        byte[] extended = new byte[payload.length + 4];
        System.arraycopy(payload, 0, extended, 0, payload.length);
        System.arraycopy(checksum, 0, extended, payload.length, 4);

        // 2. Convert to Base58
        return toBase58(extended);
    }

    private static byte[] doubleSha256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] first = sha256.digest(data);
            return sha256.digest(first);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String toBase58(byte[] input) {
        BigInteger bi = new BigInteger(1, input);

        StringBuilder sb = new StringBuilder();
        while (bi.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = bi.divideAndRemainder(BigInteger.valueOf(58));
            bi = divRem[0];
            int digit = divRem[1].intValue();
            sb.append(ALPHABET[digit]);
        }

        // leading zeros become '1's
        for (int i = 0; i < input.length && input[i] == 0; i++) {
            sb.append('1');
        }

        return sb.reverse().toString();
    }
}