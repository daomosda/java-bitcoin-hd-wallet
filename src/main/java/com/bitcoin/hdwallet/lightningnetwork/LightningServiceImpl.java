package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.model.Invoice;
import com.bitcoin.hdwallet.model.HTLC;
import com.bitcoin.hdwallet.model.OnionPacket;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.crypto.HexUtils;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Concrete implementation of LightningService.
 *
 * Manages:
 *   • Channel lifecycle  (open, capacity, balances)
 *   • HTLC state machine (add, fulfill, settle, fail)
 *   • Commitment signing (sign + wait for revoke_and_ack)
 *   • Invoice + preimage store
 *   • In-memory channel state (replace with DB for production)
 */
public class LightningServiceImpl implements LightningService {

    /**
     * All state for one payment channel.
     */
    private static final class ChannelState {

        final String channelId;
        final String remotePubKey;
        final long   capacitySat;

        // balances in millisatoshis
        long localMsat;
        long remoteMsat;

        // pending HTLCs keyed by payment hash (hex)
        final Map<String, HTLC> pendingHtlcs = new ConcurrentHashMap<>();

        // signaling: notified when remote sends revoke_and_ack
        final Object revocationLock = new Object();
        boolean revocationReceived  = false;

        // signaling: notified when an HTLC arrives
        final Object htlcLock      = new Object();
        HTLC         incomingHtlc  = null;

        // signaling: notified when preimage is revealed
        final Object preimageMonitor            = new Object();
        final Map<String, byte[]> preimageStore = new ConcurrentHashMap<>();

        ChannelState(String channelId,
                     String remotePubKey,
                     long   capacitySat,
                     long   localMsat,
                     long   remoteMsat) {
            this.channelId    = channelId;
            this.remotePubKey = remotePubKey;
            this.capacitySat  = capacitySat;
            this.localMsat    = localMsat;
            this.remoteMsat   = remoteMsat;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────
    
    // Thread-safe storage for current invoice
    private final ReentrantReadWriteLock invoiceLock = new ReentrantReadWriteLock();
    private Invoice currentInvoice;
    
    // Thread-safe storage for last opened channel ID
    private final ReentrantReadWriteLock channelLock = new ReentrantReadWriteLock();
    private String lastOpenedChannelId;

    private final BitcoinRpcClient rpc;
    private final SecureRandom     rng = new SecureRandom();

    // channelId → state
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();

    // payment hash (hex) → preimage — persisted across channel boundaries
    private final Map<String, byte[]> globalPreimageStore = new ConcurrentHashMap<>();

    // timeout waiting for remote messages
    private static final long REVOCATION_TIMEOUT_MS = 30_000L;
    private static final long HTLC_TIMEOUT_MS       = 30_000L;
    private static final long PREIMAGE_TIMEOUT_MS   = 30_000L;

    // ── Constructor ───────────────────────────────────────────────────

    public LightningServiceImpl(BitcoinRpcClient rpc) {
        this.rpc = rpc;
    }

    // ─────────────────────────────────────────────────────────────────
    // openChannel
    // ─────────────────────────────────────────────────────────────────

    /**
     * Opens a channel to a remote peer.
     *
     * In production this would:
     *   1. Craft a funding transaction using on-chain UTXOs.
     *   2. Exchange open_channel / accept_channel messages with the peer.
     *   3. Broadcast the funding tx and wait for confirmations.
     *
     * Here we simulate the channel open and record the state locally.
     *
     * @param  peerPubKey  remote node's compressed public key (hex)
     * @param  capacitySat total channel capacity the local node funds
     * @return             a unique channelId string
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public String openChannel(String peerPubKey, long capacitySat)
            throws LightningServiceException {
        AppLogger.info("[LightningService] openChannel: peer={} capacity={} sat",
            peerPubKey.substring(0, 12), capacitySat);

        validatePositive("capacitySat", capacitySat);

        // generate a unique channel ID (production: funding tx outpoint)
        String channelId = "chan-" + UUID.randomUUID().toString().substring(0, 8);

        // local starts with full capacity, remote starts at zero
        long localMsat  = capacitySat * 1_000L;
        long remoteMsat = 0L;

        ChannelState state = new ChannelState(
            channelId, peerPubKey, capacitySat, localMsat, remoteMsat);
        channels.put(channelId, state);

        AppLogger.info("[LightningService] Channel opened: id={} localMsat={} remoteMsat={}",
            channelId, localMsat, remoteMsat);

        AppLogger.info("openChannel",
            "Channel " + channelId + " opened with capacity " + capacitySat + " sat");

        return channelId;
    }

    // ─────────────────────────────────────────────────────────────────
    // pushFunds
    // ─────────────────────────────────────────────────────────────────

    /**
     * Transfers millisatoshis from local balance to remote balance.
     * Used to give the remote side inbound liquidity at channel open.
     *
     * In production this generates a push_msat in the open_channel message.
     * Here we adjust the in-memory balances directly.
     *
     * @param channelId   target channel
     * @param amountMsat  millisatoshis to push to remote
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void pushFunds(String channelId, long amountMsat)
            throws LightningServiceException {
        AppLogger.info("[LightningService] pushFunds: channel={} amount={} msat",
            channelId, amountMsat);

        ChannelState state = getChannel(channelId);
        validatePositive("amountMsat", amountMsat);

        if (amountMsat > state.localMsat) {
            throw new LightningServiceException(
                "pushFunds: insufficient local balance. " +
                "have=" + state.localMsat + " need=" + amountMsat);
        }

        state.localMsat  -= amountMsat;
        state.remoteMsat += amountMsat;

        AppLogger.info("[LightningService] After push: local={} msat remote={} msat",
            state.localMsat, state.remoteMsat);
    }

    // ─────────────────────────────────────────────────────────────────
    // signAndSend
    // ─────────────────────────────────────────────────────────────────

    /**
     * Signs the current commitment transaction and sends it to the peer,
     * then blocks until the peer's revoke_and_ack arrives.
     *
     * In production:
     *   1. Build commitment_signed message with our ECDSA signature.
     *   2. Send it to the peer over the P2P socket.
     *   3. Wait for the peer's revoke_and_ack.
     *   4. Verify the revocation key.
     *
     * Here we simulate the round-trip with a short wait.
     *
     * @param channelId  the channel undergoing a commitment update
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException
     */
    @Override
    public void signAndSend(String channelId)
            throws LightningServiceException, InterruptedException {
        AppLogger.info("[LightningService] signAndSend: channel={}", channelId);

        ChannelState state = getChannel(channelId);

        // simulate signing delay
        AppLogger.info("[LightningService] Signing commitment for channel={}", channelId);

        // wait for revoke_and_ack from remote
        waitForRevocation(state);

        AppLogger.info("[LightningService] signAndSend complete: channel={}", channelId);
    }

    private void waitForRevocation(ChannelState state)
            throws LightningServiceException, InterruptedException {
        synchronized (state.revocationLock) {
            if (!state.revocationReceived) {
                AppLogger.info("[LightningService] Waiting for revoke_and_ack " +
                          "on channel={}", state.channelId);

                // In production: the P2P message handler calls
                //   notifyRevocationReceived(channelId)
                // when a REVOKE_AND_ACK message arrives from the peer.
                //
                // For simulation we auto-trigger it after a short delay.
                simulateRemoteRevocation(state);

                state.revocationLock.wait(REVOCATION_TIMEOUT_MS);
            }

            if (!state.revocationReceived) {
                throw new LightningServiceException(
                    "Timeout waiting for revoke_and_ack on channel="
                    + state.channelId);
            }

            // reset for next commitment round
            state.revocationReceived = false;
        }
    }

    /**
     * Called by the P2P message handler when REVOKE_AND_ACK arrives.
     * In production this is triggered by the network layer.
     * @param channelId
     */
    public void notifyRevocationReceived(String channelId) {
        ChannelState state = channels.get(channelId);
        if (state == null) return;
        synchronized (state.revocationLock) {
            state.revocationReceived = true;
            state.revocationLock.notifyAll();
        }
        AppLogger.info("[LightningService] revoke_and_ack received: channel={}", channelId);
    }

    // simulate remote side acking immediately (regtest / test only)
    private void simulateRemoteRevocation(ChannelState state) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);   // tiny delay mimics network RTT
                notifyRevocationReceived(state.channelId);
            } catch (InterruptedException ignored) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // createInvoice
    // ─────────────────────────────────────────────────────────────────

    /**
     * Creates a Lightning invoice and its corresponding preimage.
     *
     * Process:
     *   1. Generate 32 random bytes as the preimage.
     *   2. SHA-256 hash the preimage → payment hash.
     *   3. Build the Invoice with amount, expiry, payee pubkey.
     *   4. Return both so the caller can store the preimage.
     *
     * @param  amountSat    invoice amount in satoshis
     * @param  payeePubKey  the receiving node's pubkey (hex)
     * @return              Map.Entry of (Invoice, preimage bytes)
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public Map.Entry<Invoice, byte[]> createInvoice(long   amountSat,
                                                     String payeePubKey)
            throws LightningServiceException {
        AppLogger.info("[LightningService] createInvoice: amount={} sat", amountSat);

        validatePositive("amountSat", amountSat);

        // 1. random preimage
        byte[] preimage = new byte[32];
        rng.nextBytes(preimage);

        // 2. payment hash = SHA-256(preimage)
        byte[] paymentHash = sha256(preimage);

        // 3. invoice expires in 1 hour
        long expiryEpoch = System.currentTimeMillis() / 1000L
            + LightningPaymentConstants.INVOICE_EXPIRY_SECONDS;

        Invoice invoice = new Invoice(
            paymentHash,
            amountSat,
            "Payment for goods",
            expiryEpoch,
            payeePubKey
        );

        String paymentHashStr = HexUtils.bytesToHex(paymentHash);
        AppLogger.info("[LightningService] Invoice created: hash={} amount={} sat",
            paymentHashStr.substring(0, 12), amountSat);

        return Map.entry(invoice, preimage);
    }

    // ─────────────────────────────────────────────────────────────────
    // storePreimage
    // ─────────────────────────────────────────────────────────────────

    /**
     * Persists the preimage so it can be retrieved when the HTLC arrives.
     * Must be called BEFORE sharing the invoice with the payer.
     *
     * @param paymentHash  SHA-256 hash of the preimage (raw bytes)
     * @param preimage     the secret 32-byte value
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void storePreimage(byte[] paymentHash, byte[] preimage)
            throws LightningServiceException {
        String hashHex = bytesToHex(paymentHash);
        globalPreimageStore.put(hashHex, Arrays.copyOf(preimage, preimage.length));
        AppLogger.info("[LightningService] Preimage stored for hash={}",
            hashHex.substring(0, 12));
    }

    // ─────────────────────────────────────────────────────────────────
    // waitForIncomingHTLC
    // ─────────────────────────────────────────────────────────────────

    /**
     * Blocks until an HTLC with the given payment hash arrives on the channel.
     *
     * In production the P2P message handler calls
     *   notifyIncomingHtlc(channelId, htlc)
     * when an update_add_htlc message is received from the peer.
     *
     * @param channelId    the channel to watch
     * @param paymentHash  the expected payment hash (raw bytes)
     * @return             the arrived HTLC
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException
     */
    @Override
    public HTLC waitForIncomingHTLC(String channelId, byte[] paymentHash)
            throws LightningServiceException, InterruptedException {
        AppLogger.info("[LightningService] waitForIncomingHTLC: channel={} hash={}",
            channelId, bytesToHex(paymentHash).substring(0, 12));

        ChannelState state = getChannel(channelId);

        synchronized (state.htlcLock) {
            if (state.incomingHtlc == null) {
                AppLogger.info("[LightningService] Waiting for HTLC on channel={}",
                    channelId);
                state.htlcLock.wait(HTLC_TIMEOUT_MS);
            }

            if (state.incomingHtlc == null) {
                throw new LightningServiceException(
                    "Timeout waiting for HTLC on channel=" + channelId);
            }

            HTLC htlc = state.incomingHtlc;
            state.incomingHtlc = null;   // consume
            return htlc;
        }
    }

    /**
     * Called by the P2P layer when update_add_htlc arrives from the peer.
     * @param channelId
     * @param htlc
     */
    public void notifyIncomingHtlc(String channelId, HTLC htlc) {
        ChannelState state = channels.get(channelId);
        if (state == null) {
            AppLogger.warn("[LightningService] notifyIncomingHtlc: unknown channel={}",
                channelId);
            return;
        }
        synchronized (state.htlcLock) {
            state.incomingHtlc = htlc;
            state.pendingHtlcs.put(bytesToHex(htlc.getPaymentHash()), htlc);
            state.htlcLock.notifyAll();
        }
        AppLogger.info("[LightningService] Incoming HTLC registered: {}", htlc);
    }

    // ─────────────────────────────────────────────────────────────────
    // fulfillHTLC
    // ─────────────────────────────────────────────────────────────────

    /**
     * Reveals the preimage to the payer, settling the HTLC in our favour.
     *
     * In production sends update_fulfill_htlc to the peer containing
     * the preimage, then updates local balance.
     *
     * @param channelId    the channel carrying the HTLC
     * @param paymentHash  identifies which HTLC to fulfill
     * @param preimage     the secret that unlocks the HTLC
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void fulfillHTLC(String channelId,
                             byte[] paymentHash,
                             byte[] preimage)
            throws LightningServiceException {
        String hashHex = bytesToHex(paymentHash);
        AppLogger.info("[LightningService] fulfillHTLC: channel={} hash={}",
            channelId, hashHex.substring(0, 12));

        ChannelState state = getChannel(channelId);

        HTLC htlc = state.pendingHtlcs.remove(hashHex);
        if (htlc == null) {
            throw new LightningServiceException(
                "fulfillHTLC: no pending HTLC for hash=" + hashHex);
        }

        // verify preimage matches hash before revealing
        if (!Arrays.equals(sha256(preimage), paymentHash)) {
            throw new LightningServiceException(
                "fulfillHTLC: preimage does not hash to payment hash");
        }

        // update balances: local receives the HTLC amount
        state.localMsat  += htlc.getAmountMsat();
        state.remoteMsat -= htlc.getAmountMsat();

        // notify waiting parties (customer side waitForPreimage)
        state.preimageStore.put(hashHex, Arrays.copyOf(preimage, preimage.length));
        synchronized (state.preimageMonitor) {
            state.preimageMonitor.notifyAll();
        }

        AppLogger.info("[LightningService] HTLC fulfilled: localMsat={} remoteMsat={}",
            state.localMsat, state.remoteMsat);
    }

    // ─────────────────────────────────────────────────────────────────
    // addHTLC
    // ─────────────────────────────────────────────────────────────────

    /**
     * Adds an outgoing HTLC (customer side — locks funds toward merchant).
     *
     * In production sends update_add_htlc to the peer, wrapped in the
     * onion packet for routing privacy.
     *
     * @param channelId   the channel to route through
     * @param htlc        the HTLC parameters
     * @param onion       the onion-encrypted routing packet
     * @param chainHeight current block height (for CLTV validation)
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void addHTLC(String      channelId,
                         HTLC        htlc,
                         OnionPacket onion,
                         int         chainHeight)
            throws LightningServiceException {
        AppLogger.info("[LightningService] addHTLC: channel={} amount={} msat cltv={}",
            channelId, htlc.getAmountMsat(), htlc.getCltvExpiry());

        ChannelState state = getChannel(channelId);

        // validate we have enough balance
        if (htlc.getAmountMsat() > state.localMsat) {
            throw new LightningServiceException(
                "addHTLC: insufficient local balance. " +
                "have=" + state.localMsat + " need=" + htlc.getAmountMsat());
        }

        // validate CLTV
        if (htlc.getCltvExpiry() <= chainHeight) {
            throw new LightningServiceException(
                "addHTLC: CLTV expiry already past. " +
                "cltvExpiry=" + htlc.getCltvExpiry() + " chainHeight=" + chainHeight);
        }

        // lock the funds in the pending HTLC
        state.localMsat -= htlc.getAmountMsat();
        String hashHex   = bytesToHex(htlc.getPaymentHash());
        state.pendingHtlcs.put(hashHex, htlc);

        // in production: send update_add_htlc + onion to peer via P2P
        AppLogger.info("[LightningService] HTLC added to channel={} hash={}",
            channelId, hashHex.substring(0, 12));

        // simulate merchant receiving it
        simulateMerchantReceivesHtlc(channelId, htlc);
    }

    // simulate the merchant's node receiving the HTLC (regtest only)
    private void simulateMerchantReceivesHtlc(String channelId, HTLC htlc) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
                notifyIncomingHtlc(channelId, htlc);
            } catch (InterruptedException ignored) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // waitForPreimage
    // ─────────────────────────────────────────────────────────────────

    /**
     * Blocks until the merchant reveals the preimage by fulfilling the HTLC.
     *
     * In production the update_fulfill_htlc message from the peer
     * contains the preimage; the P2P handler calls
     *   notifyPreimageRevealed(paymentHash, preimage)
     * which wakes this method.
     *
     * @param  paymentHash  the hash we are waiting for a preimage of
     * @return              the revealed preimage bytes
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     * @throws java.lang.InterruptedException
     */
    @Override
    public byte[] waitForPreimage(byte[] paymentHash)
            throws LightningServiceException, InterruptedException {
        String hashHex = bytesToHex(paymentHash);
        AppLogger.info("[LightningService] waitForPreimage: hash={}",
            hashHex.substring(0, 12));

        // check global store first (preimage may already be there)
        byte[] stored = globalPreimageStore.get(hashHex);
        if (stored != null) return stored;

        // search all channels
        for (ChannelState state : channels.values()) {
            synchronized (state.preimageMonitor) {
                long deadline = System.currentTimeMillis() + PREIMAGE_TIMEOUT_MS;

                while (!state.preimageStore.containsKey(hashHex)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    state.preimageMonitor.wait(remaining);
                }

                byte[] preimage = state.preimageStore.get(hashHex);
                if (preimage != null) {
                    AppLogger.info("[LightningService] Preimage received for hash={}",
                        hashHex.substring(0, 12));
                    return preimage;
                }
            }
        }

        throw new LightningServiceException(
            "Timeout waiting for preimage of hash=" + hashHex);
    }

    // ─────────────────────────────────────────────────────────────────
    // settleHTLC
    // ─────────────────────────────────────────────────────────────────

    /**
     * Removes the HTLC from pending state and updates the customer's balance.
     * Called after the customer receives and verifies the preimage.
     *
     * In production sends update_fulfill_htlc to confirm settlement.
     *
     * @param channelId   the channel carrying the HTLC
     * @param paymentHash identifies the HTLC
     * @param preimage    verified secret — balance transfer is final
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void settleHTLC(String channelId,
                            byte[] paymentHash,
                            byte[] preimage)
            throws LightningServiceException {
        String hashHex = bytesToHex(paymentHash);
        AppLogger.info("[LightningService] settleHTLC: channel={} hash={}",
            channelId, hashHex.substring(0, 12));

        ChannelState state  = getChannel(channelId);
        HTLC         htlc   = state.pendingHtlcs.remove(hashHex);

        if (htlc == null) {
            throw new LightningServiceException(
                "settleHTLC: no pending HTLC for hash=" + hashHex);
        }

        // HTLC amount moves from local-locked to remote (merchant) permanently
        // local already had the amount deducted in addHTLC
        state.remoteMsat += htlc.getAmountMsat();

        AppLogger.info("[LightningService] HTLC settled: localMsat={} remoteMsat={}",
            state.localMsat, state.remoteMsat);
    }

    // ─────────────────────────────────────────────────────────────────
    // failPendingHtlcs
    // ─────────────────────────────────────────────────────────────────

    /**
     * Fails all pending HTLCs on the channel and restores locked balances.
     * Called during error cleanup.
     *
     * In production sends update_fail_htlc for each pending HTLC.
     *
     * @param channelId  the channel to clean up
     * @throws com.bitcoin.hdwallet.lightningnetwork.LightningServiceException
     */
    @Override
    public void failPendingHtlcs(String channelId)
            throws LightningServiceException {
        AppLogger.warn("[LightningService] failPendingHtlcs: channel={}", channelId);

        ChannelState state = channels.get(channelId);
        if (state == null) {
            AppLogger.warn("[LightningService] failPendingHtlcs: channel not found={}",
                channelId);
            return;
        }

        // restore locked balance for each pending HTLC
        for (HTLC htlc : state.pendingHtlcs.values()) {
            if (htlc.getDirection() == HTLC.Direction.OFFERED) {
                state.localMsat += htlc.getAmountMsat();
                AppLogger.info("[LightningService] Restored {} msat from failed HTLC",
                    htlc.getAmountMsat());
            }
        }

        int count = state.pendingHtlcs.size();
        state.pendingHtlcs.clear();
        AppLogger.info("[LightningService] Failed {} pending HTLC(s) on channel={}",
            count, channelId);
    }

    // ─────────────────────────────────────────────────────────────────
    // printChannelBalance
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void printChannelBalance(String channelId)
            throws LightningServiceException {
        ChannelState state = getChannel(channelId);
        long localSat      = state.localMsat  / 1_000L;
        long remoteSat     = state.remoteMsat / 1_000L;
        long capacitySat   = state.capacitySat;

        AppLogger.info("[LightningService] ── Channel Balance ───────────────────");
        AppLogger.info("[LightningService]   Channel   : {}", channelId);
        AppLogger.info("[LightningService]   Capacity  : {} sat", capacitySat);
        AppLogger.info("[LightningService]   Local     : {} sat  ({} msat)",
            localSat,  state.localMsat);
        AppLogger.info("[LightningService]   Remote    : {} sat  ({} msat)",
            remoteSat, state.remoteMsat);
        AppLogger.info("[LightningService]   Pending   : {} HTLC(s)",
            state.pendingHtlcs.size());
        AppLogger.info("[LightningService] ─────────────────────────────────────");

        AppLogger.info("channelBalance",
            "Local=" + localSat + " sat  Remote=" + remoteSat + " sat");
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private ChannelState getChannel(String channelId)
            throws LightningServiceException {
        ChannelState state = channels.get(channelId);
        if (state == null) {
            throw new LightningServiceException(
                "Channel not found: " + channelId);
        }
        return state;
    }

    private static void validatePositive(String name, long value)
            throws LightningServiceException {
        if (value <= 0) {
            throw new LightningServiceException(
                name + " must be positive, got " + value);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
    
    /**
     * Gets the current active Lightning invoice
     * Returns existing invoice if not expired, otherwise creates new one
     */
    @Override
    public Invoice getCurrentInvoice() throws IOException {
        // Try read lock first (for existing valid invoice)
        invoiceLock.readLock().lock();
        try {
            // Return current invoice if it exists and is not expired
            if (currentInvoice != null && !currentInvoice.isExpired()) {
                return currentInvoice;
            }
        } finally {
            invoiceLock.readLock().unlock();
        }
        
        // Invoice expired or null - need to create new one
        // Upgrade to write lock
        invoiceLock.writeLock().lock();
        try {
            // Double-check after acquiring write lock (another thread might have created it)
            if (currentInvoice != null && !currentInvoice.isExpired()) {
                return currentInvoice;
            }
            
            // Generate new invoice using wallet RPC
            double feeSatPerVb = rpc.estimateSmartFeeSatPerVb(6);
            long confirmedBalance = (long) (rpc.getConfirmedBalanceBtc() * 100_000_000);
            
            // Example: Create invoice for 1000 satoshis or use balance
            long amountSat = Math.max(1000, confirmedBalance / 100); // 1% of balance
            String description = "Lightning payment";
            long expiryEpoch = Instant.now().getEpochSecond() + 3600; // 1 hour expiry
            
            // Generate payment hash (in production, use actual Lightning invoice generation)
            byte[] paymentHash = generatePaymentHash();
            String payeePubKey = get_node_pubkey();
            
            currentInvoice = new Invoice(
                paymentHash,
                amountSat,
                description,
                expiryEpoch,
                payeePubKey
            );
            
            return currentInvoice;
            
        } catch (BitcoinRpcException ex) {
            System.getLogger(LightningServiceImpl.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        } finally {
            invoiceLock.writeLock().unlock();
        }
        return null;
    }
    
    /**
     * Gets the ID of the last opened Lightning channel
     * Channel ID is typically the outpoint (txid:vout)
     */
    @Override
    public String getLastOpenedChannelId() {
        channelLock.readLock().lock();
        try {
            return lastOpenedChannelId;
        } finally {
            channelLock.readLock().unlock();
        }
    }
    
    /**
     * Sets the last opened channel ID (called after successfully opening a channel)
     */
    public void setLastOpenedChannelId(String channelId) {
        channelLock.writeLock().lock();
        try {
            this.lastOpenedChannelId = channelId;
        } finally {
            channelLock.writeLock().unlock();
        }
    }
    
    /**
     * Sets the current invoice (called after creating a new Lightning invoice)
     */
    public void setCurrentInvoice(Invoice invoice) {
        invoiceLock.writeLock().lock();
        try {
            this.currentInvoice = invoice;
        } finally {
            invoiceLock.writeLock().unlock();
        }
    }
    
    // ============================================================================
    // HELPER METHODS
    // ============================================================================
    
    /**
     * Generates a payment hash for the invoice (SHA-256)
     */
    private byte[] generatePaymentHash() {
        byte[] preimage = new byte[32];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(preimage);
        
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            return sha256.digest(preimage);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Gets the node's public key
     */
    private String get_node_pubkey() {
        // TODO: Replace with actual node pubkey from Lightning node
        return "02" + HexUtils.bytesToHex(new byte[32]); // Placeholder
    }
}