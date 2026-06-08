package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */


/**
 * Shared constants used by both merchant and customer payment handlers.
 */
public final class LightningPaymentConstants {

    private LightningPaymentConstants() {}

    /** Total channel capacity funded by the merchant (satoshis). */
    public static final long CHANNEL_CAPACITY_SAT = 1_000_000L;   // 0.01 BTC

    /**
     * Amount pushed to customer at channel open so they have
     * inbound liquidity to pay immediately (millisatoshis).
     */
    public static final long PUSH_AMOUNT_MSAT     =   500_000_000L; // 500 000 sat

    /** Invoice amount the merchant bills the customer (satoshis). */
    public static final long INVOICE_AMOUNT_SAT   =   100_000L;     // 100 000 sat

    /**
     * Minimum CLTV delta the merchant requires on an incoming HTLC.
     * If HTLC expiry < chainHeight + CLTV_MIN_EXPIRY_DELTA → reject.
     */
    public static final long CLTV_MIN_EXPIRY_DELTA =  144L;         // ~1 day

    /**
     * CLTV delta the customer adds when constructing an outgoing HTLC.
     * Must be >= merchant's CLTV_MIN_EXPIRY_DELTA.
     */
    public static final long CLTV_OFFERED_DELTA    =  144L;

    /** Invoice validity window in seconds. */
    public static final long INVOICE_EXPIRY_SECONDS = 3_600L;       // 1 hour

    /** Timeout waiting for peer messages (milliseconds). */
    public static final long PEER_MESSAGE_TIMEOUT_MS = 30_000L;     // 30 s
}