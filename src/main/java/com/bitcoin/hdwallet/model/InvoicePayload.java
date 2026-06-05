package com.bitcoin.hdwallet.model;

/**
 *
 * @author CONALDES
 */

/**
 * Wire representation of an Invoice — sent peer-to-peer.
 */
public final class InvoicePayload {

    private final byte[] paymentHash;
    private final long   amountSat;
    private final String description;
    private final long   expiryEpoch;
    private final String payeePubKey;
    private final String channelId;

    public InvoicePayload(byte[] paymentHash,
                           long   amountSat,
                           String description,
                           long   expiryEpoch,
                           String payeePubKey,
                           String channelId) {
        this.paymentHash  = paymentHash;
        this.amountSat    = amountSat;
        this.description  = description;
        this.expiryEpoch  = expiryEpoch;
        this.payeePubKey  = payeePubKey;
        this.channelId    = channelId;
    }

    public static InvoicePayload fromInvoice(Invoice invoice, String channelId) {
        return new InvoicePayload(
            invoice.getPaymentHash(),
            invoice.getAmountSat(),
            invoice.getDescription(),
            invoice.getExpiryEpoch(),
            invoice.getPayeePubKey(),
            channelId
        );
    }

    public byte[] getPaymentHash()  { return paymentHash;  }
    public long   getAmountSat()    { return amountSat;    }
    public String getDescription()  { return description;  }
    public long   getExpiryEpoch()  { return expiryEpoch;  }
    public String getPayeePubKey()  { return payeePubKey;  }
    public String getChannelId()    { return channelId;    }
}
