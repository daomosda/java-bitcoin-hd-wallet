package com.bitcoin.hdwallet.apisserver;

import java.math.BigDecimal;
import org.json.JSONObject;

/**
 *
 * @author CONALDES
 */

public class ApiSendRequest {
    private final String toAddress;
    private final BigDecimal amountBtc;
    private final int targetBlocks;

    public ApiSendRequest(String toAddress, BigDecimal amountBtc, int targetBlocks) {
        this.toAddress = toAddress;
        this.amountBtc = amountBtc;
        this.targetBlocks = targetBlocks;
    }

    public String getToAddress() {
        return toAddress;
    }

    public BigDecimal getAmountBtc() {
        return amountBtc;
    }

    public int getTargetBlocks() {
        return targetBlocks;
    }

    public static ApiSendRequest fromJson(String body) {
        JSONObject json = new JSONObject(body);
        String toAddress = json.getString("toAddress");
        String amountStr = json.getString("amountBtc");
        BigDecimal amountBtc = new BigDecimal(amountStr);

        int targetBlocks = json.has("targetBlocks")
                ? json.getInt("targetBlocks")
                : 6;

        return new ApiSendRequest(toAddress, amountBtc, targetBlocks);
    }
}