package com.bitcoin.hdwallet.chaindata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 *
 * @author CONALDES
 */
public class TxSignHelpers {
    
    public static byte[] intToLittleEndian(int val) {
        return ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(val).array();
    }

    public static byte[] longToLittleEndian(long val) {
        return ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(val).array();
    }
    
    public static byte[] encodeVarInt(long value) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        if (value < 0xfd) {
            bos.write((byte) value);
        } else if (value <= 0xffff) {
            bos.write(0xfd);
            bos.write((byte) (value & 0xff));
            bos.write((byte) ((value >> 8) & 0xff));
        } else if (value <= 0xffffffffL) {
            bos.write(0xfe);
            bos.write(intToLittleEndian((int) value));
        } else {
            bos.write(0xff);
            bos.write(longToLittleEndian(value));
        }

        return bos.toByteArray();
    }
    
    public static byte[] reverseHex(String hex) {

        byte[] bytes = hexStringToByteArray(hex);

        for (int i = 0; i < bytes.length / 2; i++) {
            byte temp = bytes[i];
            bytes[i] = bytes[bytes.length - 1 - i];
            bytes[bytes.length - 1 - i] = temp;
        }

        return bytes;
    }
    
    public static byte[] sha256(byte[] data) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte) ((Character.digit(s.charAt(i), 16) << 4)
                            + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }    
}
