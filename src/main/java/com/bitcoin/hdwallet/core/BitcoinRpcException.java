package com.bitcoin.hdwallet.core;

/**
 *
 * @author CONALDES
 */

public class BitcoinRpcException extends Exception {
    private final int errorCode;

    public BitcoinRpcException(String message) {
        super(message);
        this.errorCode = -1;
    }

    public BitcoinRpcException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = -1;
    }

    public BitcoinRpcException(int errorCode, String message) {
        super("RPC Error " + errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}