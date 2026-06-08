package com.bitcoin.hdwallet.apisserver;

import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

public class ApiCustomerPaymentRequest {
    private final String channelId;
    private final String pubKeyHex;

    public ApiCustomerPaymentRequest(String channelId, String pubKeyHex) {
        this.channelId = channelId;
        this.pubKeyHex = pubKeyHex;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getPubKeyHex() {
        return pubKeyHex;
    }

    public byte[] getPubKeyBytes() {
        return hexStringToByteArray(pubKeyHex);
    }

    public static ApiCustomerPaymentRequest fromJson(String body) {
        JSONObject json = new JSONObject(body);
        String channelId = json.getString("channelId");
        String pubKeyHex = json.getString("pubKeyHex");
        return new ApiCustomerPaymentRequest(channelId, pubKeyHex);
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                +  Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}