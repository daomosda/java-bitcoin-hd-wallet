package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

public final class HexUtils {

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    /**
     * Helper method to convert a byte array to a hexadecimal string for printing.
     * @param bytes
     * @return 
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    public static byte[] hexToBytes(String hex) {
        if (hex == null) return null;

        // Remove any potential whitespace or "0x" prefix
        hex = hex.trim().replace(" ", "");
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }

        // If odd length, prepend a 0
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }

        int length = hex.length();
        byte[] bytes = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            // Extract two hex characters
            String hexByte = hex.substring(i, i + 2);

            // Convert to byte value
            bytes[i / 2] = (byte) Integer.parseInt(hexByte, 16);
        }

        return bytes;
    }
    
    //private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    public static String encode(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] decode(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) (
                    (Character.digit(hex.charAt(i), 16) << 4)
                            + Character.digit(hex.charAt(i + 1), 16)
            );
        }
        return out;
    }
}

