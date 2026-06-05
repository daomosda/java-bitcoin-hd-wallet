package com.bitcoin.hdwallet.model;

/**
 *
 * @author CONALDES
 */


public final class Message {

    public enum Type {
        LN_PUBKEY,      // merchant ← customer pubkey introduction
        CHANNEL_ID,     // merchant → customer channel ID notification
        INVOICE,        // merchant → customer invoice delivery
        PREIMAGE,       // merchant → customer payment proof
        HTLC_ADD,       // customer → merchant HTLC offer
        HTLC_FULFILL,   // merchant → customer HTLC fulfillment
        HTLC_FAIL,      // either  → either   HTLC failure
        COMMITMENT_SIG, // commitment signature
        REVOKE_AND_ACK  // revocation acknowledgment
    }

    private final Type   type;
    private final String payload;   // JSON or hex string
    private final String senderId;

    public Message(Type type, String payload, String senderId) {
        this.type     = type;
        this.payload  = payload;
        this.senderId = senderId;
    }

    /** Factory for invoice messages. */
    public static Message invoice(InvoicePayload payload, String senderId) {
        // serialise InvoicePayload to JSON string
        String json = String.format(
            "{\"paymentHash\":\"%s\",\"amountSat\":%d," +
            "\"expiryEpoch\":%d,\"channelId\":\"%s\"}",
            payload.getPaymentHash(),
            payload.getAmountSat(),
            payload.getExpiryEpoch(),
            payload.getChannelId()
        );
        return new Message(Type.INVOICE, json, senderId);
    }

    public Type   getType()     { return type;     }
    public String getPayload()  { return payload;  }
    public String getSenderId() { return senderId; }
}