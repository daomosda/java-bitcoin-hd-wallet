package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.chainindexing.Bytes;
import com.bitcoin.hdwallet.model.HopPayload;
import com.bitcoin.hdwallet.model.OnionPacket;
import com.bitcoin.hdwallet.crypto.Hkdf;
import com.bitcoin.hdwallet.crypto.Secp256k1;
import com.bitcoin.hdwallet.crypto.Crypto;
import java.util.*;

public class OnionBuilder {

    public static OnionPacket build(List<byte[]> pubKeys, List<HopPayload> payloads) {

        byte[] packet = new byte[0];

        for (int i = pubKeys.size() - 1; i >= 0; i--) {

            byte[] sharedSecret = Secp256k1.ecdh(
                Crypto.random32(), pubKeys.get(i)
            );

            byte[] rho = Hkdf.expand(sharedSecret, "rho".getBytes(), 32);

            byte[] payload = payloads.get(i).serialize();

            packet = Bytes.concat(
                payload,
                xor(packet, rho)
            );
        }

        return new OnionPacket(packet);
    }
    
    public OnionPacket buildRoute(
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

        // OnionBuilder constructs version, ephemeralKey, routingInfo, hmac
        return OnionBuilder.build(pubKeys, payloads);
    }


    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[Math.max(a.length, b.length)];

        for (int i = 0; i < out.length; i++) {
            byte x = i < a.length ? a[i] : 0;
            byte y = i < b.length ? b[i] : 0;
            out[i] = (byte) (x ^ y);
        }
        return out;
    }
}

