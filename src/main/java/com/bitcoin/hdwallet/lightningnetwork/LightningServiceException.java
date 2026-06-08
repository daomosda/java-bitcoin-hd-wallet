package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */


public class LightningServiceException extends Exception {
    public LightningServiceException(String message) { super(message); }
    public LightningServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}