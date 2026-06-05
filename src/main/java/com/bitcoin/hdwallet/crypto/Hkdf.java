package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.chainindexing.Bytes;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hkdf {

    public static byte[] extract(byte[] salt, byte[] ikm) {
        return hmacSha256(salt, ikm);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) {

        byte[] result = new byte[length];
        byte[] t = new byte[0];

        int offset = 0;
        int counter = 1;

        while (offset < length) {

            t = hmacSha256(prk, Bytes.concat(t, info, new byte[]{(byte) counter}));

            int copyLen = Math.min(t.length, length - offset);
            System.arraycopy(t, 0, result, offset, copyLen);

            offset += copyLen;
            counter++;
        }

        return result;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (IllegalStateException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static byte[] deriveKey(byte[] secret, String label) {
        byte[] prk = Hkdf.extract(new byte[32], secret);
        return Hkdf.expand(prk, label.getBytes(), 32);
    }
    
    public static byte[] mu(byte[] secret) {
        return deriveKey(secret, "mu");
    }
}