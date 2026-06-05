package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author CONALDES
 */

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.util.Arrays;

public class RFC6979 {

    public static BigInteger generateK(
            BigInteger privKey,
            byte[] hash,
            BigInteger n
    ) throws Exception {

        byte[] x =
                to32Bytes(privKey);

        byte[] k =
                new byte[32];

        byte[] v =
                new byte[32];

        Arrays.fill(v, (byte) 0x01);

        Mac mac =
                Mac.getInstance("HmacSHA256");

        /*
         * k = HMAC(k, v || 0x00 || x || h1)
         */

        mac.init(new SecretKeySpec(k, "HmacSHA256"));

        mac.update(v);
        mac.update((byte) 0x00);
        mac.update(x);
        mac.update(hash);

        k = mac.doFinal();

        /*
         * v = HMAC(k, v)
         */

        mac.init(new SecretKeySpec(k, "HmacSHA256"));

        v = mac.doFinal(v);

        /*
         * k = HMAC(k, v || 0x01 || x || h1)
         */

        mac.init(new SecretKeySpec(k, "HmacSHA256"));

        mac.update(v);
        mac.update((byte) 0x01);
        mac.update(x);
        mac.update(hash);

        k = mac.doFinal();

        /*
         * v = HMAC(k, v)
         */

        mac.init(new SecretKeySpec(k, "HmacSHA256"));

        v = mac.doFinal(v);

        while (true) {

            mac.init(new SecretKeySpec(k, "HmacSHA256"));

            v = mac.doFinal(v);

            BigInteger candidate =
                    new BigInteger(1, v);

            if (candidate.signum() > 0 &&
                    candidate.compareTo(n) < 0) {

                return candidate;
            }

            mac.init(new SecretKeySpec(k, "HmacSHA256"));

            mac.update(v);
            mac.update((byte) 0x00);

            k = mac.doFinal();

            mac.init(new SecretKeySpec(k, "HmacSHA256"));

            v = mac.doFinal(v);
        }
    }

    private static byte[] to32Bytes(
            BigInteger v
    ) {

        byte[] b =
                v.toByteArray();

        if (b.length == 32) {
            return b;
        }

        byte[] out =
                new byte[32];

        if (b.length > 32) {

            System.arraycopy(
                    b,
                    b.length - 32,
                    out,
                    0,
                    32
            );

        } else {

            System.arraycopy(
                    b,
                    0,
                    out,
                    32 - b.length,
                    b.length
            );
        }

        return out;
    }
}
