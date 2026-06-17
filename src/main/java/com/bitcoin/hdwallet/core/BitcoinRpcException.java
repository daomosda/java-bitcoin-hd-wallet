/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Other/File.java to edit this template
 */
package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
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