/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

/** Thrown for any hardware wallet communication or protocol error. */
public class HardwareWalletException extends Exception {

    public enum Reason {
        DEVICE_NOT_FOUND,
        DEVICE_LOCKED,          // needs PIN entry
        USER_CANCELLED,         // user pressed cancel on device
        WRONG_FINGERPRINT,      // PSBT not for this device's keys
        COMMUNICATION_ERROR,    // USB write/read failure
        UNSUPPORTED_OPERATION,
        TIMEOUT
    }

    private final Reason reason;

    public HardwareWalletException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HardwareWalletException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}