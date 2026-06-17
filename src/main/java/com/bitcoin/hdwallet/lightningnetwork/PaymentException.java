/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */

public class PaymentException extends Exception {

    public enum PaymentStage {
        PEER_DISCOVERY,
        CHANNEL_OPEN,
        CHANNEL_NOTIFY,
        PUSH_FUNDS,
        COMMITMENT_SIGN,
        INVOICE_CREATE,
        INVOICE_DELIVER,
        HTLC_WAIT,
        HTLC_VALIDATE,
        HTLC_FULFILL,
        HTLC_SETTLE,
        FINAL_COMMIT,
        CLEANUP,
        UNKNOWN
    }

    private final PaymentStage stage;
    private final String       channelId;

    public PaymentException(String message) {
        super(message);
        this.stage     = PaymentStage.UNKNOWN;
        this.channelId = null;
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
        this.stage     = PaymentStage.UNKNOWN;
        this.channelId = null;
    }

    public PaymentException(PaymentStage stage, String channelId, String message) {
        super(message);
        this.stage     = stage;
        this.channelId = channelId;
    }

    public static PaymentException wrap(PaymentStage stage,
                                         String       channelId,
                                         Throwable    cause) {
        PaymentException e = new PaymentException(
            "[" + stage + "] " + cause.getMessage(), cause);
        return e;
    }

    public PaymentStage getStage()     { return stage;     }
    public String       getChannelId() { return channelId; }
}