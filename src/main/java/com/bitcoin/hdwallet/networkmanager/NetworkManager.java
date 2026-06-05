package com.bitcoin.hdwallet.networkmanager;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.model.InvoicePayload;
import com.bitcoin.hdwallet.model.Peer;
import java.util.Optional;

public interface NetworkManager {

    /** Blocks until a remote peer sends their LN pubkey.
     * @return 
     * @throws java.lang.InterruptedException */
    String waitForLnPubKey() throws InterruptedException;

    /** Resolves a peer ID from their pubkey string.
     * @param pubKeyStr
     * @return  */
    String getPeerByPubKey(String pubKeyStr);
    
    void addPeer(Peer peer);

    /** Finds a connected peer by ID.
     * @param peerId
     * @return  */
    Optional<Peer> findPeer(String peerId);

    /** Finds the merchant peer (used by customer side).
     * @return 
     * @throws java.lang.InterruptedException */
    String findMerchantPeer() throws InterruptedException;

    /** Sends a raw message to a peer by ID.
     * @param peerId
     * @param message */
    void sendToPeer(String peerId, String message);

    /** Our local node identity string.
     * @return  */
    String getLocalId();

    /** Returns the underlying blockchain node.
     * @return  */
    //BTCHDMBWalletApp getBlockChainNode();

    /** Blocks until an invoice payload arrives over P2P.
     * @return 
     * @throws java.lang.InterruptedException */
    InvoicePayload waitForInvoice() throws InterruptedException;
}