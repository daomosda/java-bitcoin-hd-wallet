package com.bitcoin.hdwallet.keymanagement;

/**
 *
 * @author CONALDES
 */


import java.math.BigInteger;

public class ECDSASignature {

    private final BigInteger r;
    private final BigInteger s;

    public ECDSASignature(BigInteger r, BigInteger s) {
        this.r = r;
        this.s = s;
    }

    public BigInteger r() {
        return r;
    }

    public BigInteger s() {
        return s;
    }

    public byte[] encodeToDER() {

        byte[] rBytes = trim(r.toByteArray());
        byte[] sBytes = trim(s.toByteArray());

        int totalLength =
                2 + rBytes.length +
                2 + sBytes.length;

        byte[] der = new byte[2 + totalLength];

        int p = 0;

        der[p++] = 0x30;
        der[p++] = (byte) totalLength;

        der[p++] = 0x02;
        der[p++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, p, rBytes.length);
        p += rBytes.length;

        der[p++] = 0x02;
        der[p++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, p, sBytes.length);

        return der;
    }

    private byte[] trim(byte[] in) {

        if (in.length > 1 && in[0] == 0x00) {
            byte[] out = new byte[in.length - 1];
            System.arraycopy(in, 1, out, 0, out.length);
            return out;
        }

        return in;
    }
}