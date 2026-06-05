package com.bitcoin.hdwallet.model;

import com.bitcoin.hdwallet.chainindexing.Bytes;


/**
 *
 * @author CONALDES
 */

public final class HopPayload {
    
    private final byte[] pubKey;
    private final long amountMsat;
    private final int cltv;

    public HopPayload(byte[] pubKey, long amountMsat, int cltv) {
        this.pubKey = pubKey;
        this.amountMsat = amountMsat;
        this.cltv = cltv;
    }

    public byte[] serialize() {
        return Bytes.concat(
            pubKey,
            longToBytes(amountMsat),
            intToBytes(cltv)
        );
    }

    private byte[] longToBytes(long v) {
        return java.nio.ByteBuffer.allocate(8).putLong(v).array();
    }

    private byte[] intToBytes(int v) {
        return java.nio.ByteBuffer.allocate(4).putInt(v).array();
    }
}