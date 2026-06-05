package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.model.HTLC;
import com.bitcoin.hdwallet.model.Invoice;
import com.bitcoin.hdwallet.model.OnionPacket;
import java.io.IOException;
import java.util.Map;

public interface LightningService {

    /** Opens a channel to peer, returns channelId.
     * @param peerPubKey
     * @param capacitySat
     * @return 
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    String openChannel(String peerPubKey, long capacitySat)
        throws LightningServiceException;

    /** Pushes satoshis to the remote side.
     * @param channelId
     * @param amountMsat
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void pushFunds(String channelId, long amountMsat)
        throws LightningServiceException;

    /** Signs the current commitment and waits for revoke_and_ack.
     * @param channelId
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException */
    void signAndSend(String channelId)
        throws LightningServiceException, InterruptedException;

    /** Creates invoice + preimage pair.
     * @param amountSat
     * @param payeePubKey
     * @return 
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    Map.Entry<Invoice, byte[]> createInvoice(long amountSat, String payeePubKey)
        throws LightningServiceException;

    /** Persists the preimage keyed by payment hash.
     * @param paymentHash
     * @param preimage
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void storePreimage(byte[] paymentHash, byte[] preimage)
        throws LightningServiceException;

    /** Blocks until an HTLC arrives for the given hash.
     * @param channelId
     * @param paymentHash
     * @return 
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException */
    HTLC waitForIncomingHTLC(String channelId, byte[] paymentHash)
        throws LightningServiceException, InterruptedException;

    /** Reveals preimage to fulfill an HTLC.
     * @param channelId
     * @param paymentHash
     * @param preimage
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void fulfillHTLC(String channelId, byte[] paymentHash, byte[] preimage)
        throws LightningServiceException;

    /** Adds an outgoing HTLC to the channel.
     * @param channelId
     * @param htlc
     * @param onion
     * @param chainHeight
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void addHTLC(String channelId, HTLC htlc, OnionPacket onion, int chainHeight)
        throws LightningServiceException;

    /** Blocks until the preimage for the hash is revealed.
     * @param paymentHash
     * @return 
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException */
    byte[] waitForPreimage(byte[] paymentHash)
        throws LightningServiceException, InterruptedException;

    /** Settles a received HTLC using the preimage.
     * @param channelId
     * @param paymentHash
     * @param preimage
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void settleHTLC(String channelId, byte[] paymentHash, byte[] preimage)
        throws LightningServiceException;

    /** Fails all pending HTLCs on the channel (cleanup).
     * @param channelId
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void failPendingHtlcs(String channelId)
        throws LightningServiceException;

    /** Prints current channel balance to log.
     * @param channelId
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException */
    void printChannelBalance(String channelId)
        throws LightningServiceException;
    
    /**
     * Gets the current active Lightning invoice
     * Returns existing invoice if not expired, otherwise creates new one
     * @return 
     * @throws java.io.IOException
     */
    Invoice getCurrentInvoice() throws IOException;
    
    /**
     * Gets the ID of the last opened Lightning channel
     * Channel ID is typically the outpoint (txid:vout)
     * @return 
     */
    String getLastOpenedChannelId();
}