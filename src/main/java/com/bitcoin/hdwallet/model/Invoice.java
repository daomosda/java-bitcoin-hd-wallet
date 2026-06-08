package com.bitcoin.hdwallet.model;

/**
 *
 * @author DAOMOSDA
 */


import com.bitcoin.hdwallet.crypto.HexUtils;
import java.time.Instant;

public final class Invoice {

    private final byte[] paymentHash;    // hex string
    private final long   amountSat;
    private final String description;
    private final long   expiryEpoch;   // unix timestamp
    private final String payeePubKey;

    public Invoice(byte[] paymentHash,
                   long   amountSat,
                   String description,
                   long   expiryEpoch,
                   String payeePubKey) {
        this.paymentHash  = paymentHash;
        this.amountSat    = amountSat;
        this.description  = description;
        this.expiryEpoch  = expiryEpoch;
        this.payeePubKey  = payeePubKey;
    }

    public byte[] getPaymentHash()  { return paymentHash;  }
    public long   getAmountSat()    { return amountSat;    }
    public String getDescription()  { return description;  }
    public long   getExpiryEpoch()  { return expiryEpoch;  }
    public String getPayeePubKey()  { return payeePubKey;  }

    public boolean isExpired() {
        return Instant.now().getEpochSecond() > expiryEpoch;
    }

    /** Reconstructs an Invoice from the wire payload.
     * @param payload
     * @return  */
    public static Invoice toInvoice(InvoicePayload payload) {
        return new Invoice(
            payload.getPaymentHash(),
            payload.getAmountSat(),
            payload.getDescription(),
            payload.getExpiryEpoch(),
            payload.getPayeePubKey()
        );
    }

    @Override
    public String toString() {
        String paymentHashStr = HexUtils.bytesToHex(paymentHash);
        return "Invoice{hash=" + paymentHashStr.substring(0, 12) +
               " amount=" + amountSat + " sat" +
               " expired=" + isExpired() + "}";
    }
}