package com.bitcoin.hdwallet.apisserver;

import org.json.JSONObject;

/**
 *
 * @author CONALDES
 */

public class ApiReceiveRequest {
    private final long capacitySat;
    private final int chainHeight;

    public ApiReceiveRequest(long capacitySat, int chainHeight) {
        this.capacitySat = capacitySat;
        this.chainHeight = chainHeight;
    }

    public long getCapacitySat() {
        return capacitySat;
    }

    public int getChainHeight() {
        return chainHeight;
    }

    public static ApiReceiveRequest fromJson(String body) {
        JSONObject json = new JSONObject(body);
        long capacitySat = json.getLong("capacitySat");
        int chainHeight = json.getInt("chainHeight");
        return new ApiReceiveRequest(capacitySat, chainHeight);
    }
}
