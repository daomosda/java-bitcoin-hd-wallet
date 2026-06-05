package com.bitcoin.hdwallet.model;

import com.bitcoin.hdwallet.lightningnetwork.OnionBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @author CONALDES
 */

public final class OnionPacket {

    private byte version;
    private byte[] ephemeralKey;
    private byte[] routingInfo;
    private byte[] hmac;
    
    private byte[] data;

    public OnionPacket(byte[] data) {
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }
    
    public OnionPacket(
            byte version,
            byte[] ephemeralKey,
            byte[] routingInfo,
            byte[] hmac) {

        this.version = version;
        this.ephemeralKey = ephemeralKey;
        this.routingInfo = routingInfo;
        this.hmac = hmac;
    }
        
    public static OnionPacket buildRoute(
        List<byte[]> pubKeys,
        long amountSat,
        byte[] paymentHash) {

        List<HopPayload> payloads = new ArrayList<>();

        for (byte[] pk : pubKeys) {
            payloads.add(new HopPayload(
                pk,
                amountSat * 1000,
                144
            ));
        }

        return OnionBuilder.build(pubKeys, payloads);
    }  

    // Constructor from raw bytes: parse serialized onion
    public static OnionPacket fromBytes(byte[] data) {
        // Adjust lengths to match your actual format:
        int pos = 0;

        byte version = data[pos++];

        int ephLen = 33;  // or 32
        byte[] eph = Arrays.copyOfRange(data, pos, pos + ephLen);
        pos += ephLen;

        int routingLen = 1300; // example; align with OnionBuilder
        byte[] routing = Arrays.copyOfRange(data, pos, pos + routingLen);
        pos += routingLen;

        int hmacLen = 32;
        byte[] hmac = Arrays.copyOfRange(data, pos, pos + hmacLen);

        OnionPacket pkt = new OnionPacket(version, eph, routing, hmac);
        pkt.data = Arrays.copyOf(data, data.length); // cache original
        return pkt;
    }
}