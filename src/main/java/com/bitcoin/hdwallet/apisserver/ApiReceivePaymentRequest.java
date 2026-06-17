/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.apisserver;

import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

public class ApiReceivePaymentRequest {
    private final long capacitySat;
    private final int chainHeight;

    public ApiReceivePaymentRequest(long capacitySat, int chainHeight) {
        this.capacitySat = capacitySat;
        this.chainHeight = chainHeight;
    }

    public long getCapacitySat() {
        return capacitySat;
    }

    public int getChainHeight() {
        return chainHeight;
    }

    public static ApiReceivePaymentRequest fromJson(String body) {
        JSONObject json = new JSONObject(body);
        long capacitySat = json.getLong("capacitySat");
        int chainHeight = json.getInt("chainHeight");
        return new ApiReceivePaymentRequest(capacitySat, chainHeight);
    }
}