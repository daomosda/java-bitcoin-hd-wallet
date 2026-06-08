package com.bitcoin.hdwallet.apisserver;

import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

public class ApiDelegationRequest {
    private final int workerId;

    public ApiDelegationRequest(int workerId) {
        this.workerId = workerId;
    }

    public int getWorkerId() {
        return workerId;
    }

    public static ApiDelegationRequest fromJson(String body) {
        JSONObject json = new JSONObject(body);
        int workerId = json.getInt("workerId");
        return new ApiDelegationRequest(workerId);
    }
}