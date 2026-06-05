package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author CONALDES
 */


public class LightningServiceException extends Exception {
    public LightningServiceException(String message) { super(message); }
    public LightningServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}