package com.bitcoin.hdwallet.model;

/**
 *
 * @author DAOMOSDA
 */


public final class HTLC {

    public enum Direction { OFFERED, RECEIVED }

    private final String    id;
    private final byte[]    paymentHash;
    private final long      amountMsat;
    private final long      cltvExpiry;
    private final Direction direction;

    public HTLC(String    id,
                byte[]    paymentHash,
                long      amountMsat,
                long      cltvExpiry,
                Direction direction) {
        this.id          = id;
        this.paymentHash = paymentHash;
        this.amountMsat  = amountMsat;
        this.cltvExpiry  = cltvExpiry;
        this.direction   = direction;
    }

    public String    getId()          { return id;          }
    public byte[]    getPaymentHash() { return paymentHash; }
    public long      getAmountMsat()  { return amountMsat;  }
    public long      getCltvExpiry()  { return cltvExpiry;  }
    public Direction getDirection()   { return direction;   }

    @Override
    public String toString() {
        return "HTLC{id=" + id +
               " msat=" + amountMsat +
               " cltv=" + cltvExpiry +
               " dir="  + direction  + "}";
    }
}