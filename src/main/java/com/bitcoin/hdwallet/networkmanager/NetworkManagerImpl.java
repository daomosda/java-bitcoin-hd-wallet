package com.bitcoin.hdwallet.networkmanager;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.model.InvoicePayload;
import com.bitcoin.hdwallet.model.Message;
import com.bitcoin.hdwallet.model.Peer;
import com.bitcoin.hdwallet.crypto.Crypto;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.crypto.HexUtils;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.util.encoders.Hex;

/**
 * Concrete implementation of NetworkManager.
 *
 * Manages:
 *   • Connected peer registry
 *   • Inbound message queues (one per message type)
 *   • Peer discovery (merchant / customer lookup)
 *   • Message sending (raw string and typed Message)
 */
public class NetworkManagerImpl implements NetworkManager {

    // ── Peer registry ─────────────────────────────────────────────────

    /** All currently connected peers keyed by peerId. */
    private final Map<String, Peer> connectedPeers = new ConcurrentHashMap<>();

    /** pubKey (hex) → peerId reverse lookup. */
    private final Map<String, String> pubKeyToPeerId = new ConcurrentHashMap<>();

    // ── Inbound message queues ─────────────────────────────────────────

    /** Receives LN pubkey introductions from remote peers. */
    private final BlockingQueue<String>         lnPubKeyQueue =
        new LinkedBlockingQueue<>();

    /** Receives invoice payloads from the merchant. */
    private final BlockingQueue<InvoicePayload> invoiceQueue  =
        new LinkedBlockingQueue<>();

    /** Receives channel ID notifications. */
    private final BlockingQueue<String>         channelIdQueue =
        new LinkedBlockingQueue<>();

    // ── Node identity ─────────────────────────────────────────────────

    private final String localId;
    //private final BTCHDMBWalletApp blockChainNode;   // your existing node object

    // timeouts
    private static final long PEER_WAIT_MS    = 60_000L;
    private static final long MESSAGE_WAIT_MS = 30_000L;

    // ── Constructor ───────────────────────────────────────────────────

    public NetworkManagerImpl(byte[] pubKeyBytes) {

        if (pubKeyBytes == null) {
            throw new IllegalStateException("NetworkManagerImpl: node identity bytes are null");
        }

        this.localId = Hex.toHexString(Crypto.sha256(pubKeyBytes)).substring(0, 16);
    }

    // ─────────────────────────────────────────────────────────────────
    // Peer management — called by your P2P layer when peers connect
    // ─────────────────────────────────────────────────────────────────

    /**
     * Registers a newly connected peer.
     * Call this from your P2P accept loop when a new TCP connection arrives.
     * @param peer
     */
    @Override
    public void addPeer(Peer peer) {
        connectedPeers.put(peer.getId(), peer);
        if (peer.getPubKey() != null && !peer.getPubKey().isEmpty()) {
            pubKeyToPeerId.put(peer.getPubKey(), peer.getId());
        }
        AppLogger.info("[NetworkManager] Peer connected: id={} pubKey={}",
            peer.getId(),
            peer.getPubKey() != null
                ? peer.getPubKey().substring(0, 12) : "unknown");
    }

    /**
     * Removes a disconnected peer.
     * @param peerId
     */
    public void removePeer(String peerId) {
        Peer peer = connectedPeers.remove(peerId);
        if (peer != null && peer.getPubKey() != null) {
            pubKeyToPeerId.remove(peer.getPubKey());
        }
        AppLogger.info("[NetworkManager] Peer disconnected: id={}", peerId);
    }

    // ─────────────────────────────────────────────────────────────────
    // Inbound message dispatch — called by your P2P message handler
    // ─────────────────────────────────────────────────────────────────

    /**
     * Dispatches an inbound Message to the correct queue.
     * Call this whenever a raw Message is deserialized from the wire.
     * @param message
     */
    public void onMessageReceived(Message message) {
        AppLogger.info("[NetworkManager] Message received: type={} from={}",
            message.getType(), message.getSenderId());

        switch (message.getType()) {

            case LN_PUBKEY -> {
                // customer is introducing their LN pubkey
                String pubKey = message.getPayload();
                lnPubKeyQueue.offer(pubKey);
                // update reverse lookup if peer is already registered
                String peerId = message.getSenderId();
                connectedPeers.computeIfPresent(peerId, (id, peer) -> {
                    peer.setPubKey(pubKey);
                    return peer;
                });
                pubKeyToPeerId.put(pubKey, peerId);
                AppLogger.info("[NetworkManager] LN pubkey received from {}: {}",
                    peerId, pubKey.substring(0, 12));
            }

            case INVOICE -> {
                // merchant sent an invoice payload (JSON)
                InvoicePayload payload = parseInvoicePayload(message.getPayload());
                if (payload != null) invoiceQueue.offer(payload);
            }

            case CHANNEL_ID -> {
                // merchant notified us of the channel ID
                channelIdQueue.offer(message.getPayload());
                AppLogger.info("[NetworkManager] Channel ID received: {}",
                    message.getPayload());
            }

            default ->
                AppLogger.warn("[NetworkManager] Unhandled message type: {}",
                    message.getType());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // NetworkManager interface
    // ─────────────────────────────────────────────────────────────────

    /**
     * Blocks until a remote peer sends their LN public key.
     * Used by the merchant to learn who is about to pay.
     *
     * The customer calls sendToPeer(merchantId, codaPubKeyStr)
     * which triggers onMessageReceived(LN_PUBKEY) on the merchant side.
     * @return 
     * @throws java.lang.InterruptedException
     */
    @Override
    public String waitForLnPubKey() throws InterruptedException {
        AppLogger.info("[NetworkManager] Waiting for customer LN pubkey...");

        String pubKey = lnPubKeyQueue.poll(PEER_WAIT_MS, TimeUnit.MILLISECONDS);
        if (pubKey == null) {
            throw new InterruptedException(
                "Timeout waiting for LN pubkey after " + PEER_WAIT_MS + " ms");
        }

        AppLogger.info("[NetworkManager] Customer LN pubkey received: {}",
            pubKey.substring(0, 12));
        return pubKey;
    }

    /**
     * Resolves a connected peer's ID from their LN pubkey.
     *
     * @param  pubKeyStr  the pubkey hex string received in waitForLnPubKey()
     * @return            peerId that can be passed to findPeer()
     */
    @Override
    public String getPeerByPubKey(String pubKeyStr) {
        String peerId = pubKeyToPeerId.get(pubKeyStr);
        if (peerId == null) {
            // fallback: search peer list
            for (Peer peer : connectedPeers.values()) {
                if (pubKeyStr.equals(peer.getPubKey())) {
                    peerId = peer.getId();
                    pubKeyToPeerId.put(pubKeyStr, peerId);
                    break;
                }
            }
        }
        if (peerId == null) {
            AppLogger.warn("[NetworkManager] getPeerByPubKey: no peer found for {}",
                pubKeyStr.substring(0, 12));
        }
        return peerId;
    }

    /**
     * Returns a peer by their ID, or empty if not connected.
     * @param peerId
     * @return 
     */
    @Override
    public Optional<Peer> findPeer(String peerId) {
        if (peerId == null) return Optional.empty();
        return Optional.ofNullable(connectedPeers.get(peerId));
    }

    /**
     * Finds the merchant peer.
     * Strategy: find the first connected peer flagged as a merchant,
     * or the first connected peer if only one is connected.
     * @return 
     * @throws java.lang.InterruptedException
     */
    @Override
    public String findMerchantPeer() throws InterruptedException {
        AppLogger.info("[NetworkManager] Looking for merchant peer...");

        // wait up to PEER_WAIT_MS for at least one peer
        long deadline = System.currentTimeMillis() + PEER_WAIT_MS;
        while (connectedPeers.isEmpty()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new InterruptedException(
                    "Timeout: no peers connected after " + PEER_WAIT_MS + " ms");
            }
            Thread.sleep(Math.min(500, remaining));
        }

        // prefer peer explicitly flagged as merchant
        for (Peer peer : connectedPeers.values()) {
            if (peer.isMerchant()) {
                AppLogger.info("[NetworkManager] Merchant peer found: {}", peer.getId());
                return peer.getId();
            }
        }

        // fall back to first connected peer (single-peer regtest setup)
        String peerId = connectedPeers.keySet().iterator().next();
        AppLogger.info("[NetworkManager] Using first peer as merchant: {}", peerId);
        return peerId;
    }

    /**
     * Sends a raw string message to a peer.
     * Used by the customer to send their LN pubkey to the merchant.
     * @param peerId
     * @param payload
     */
    @Override
    public void sendToPeer(String peerId, String payload) {
        Peer peer = connectedPeers.get(peerId);
        if (peer == null) {
            AppLogger.warn("[NetworkManager] sendToPeer: peer not found id={}", peerId);
            return;
        }
        Message msg = new Message(Message.Type.LN_PUBKEY, payload, localId);
        peer.sendMessage(msg);
        AppLogger.info("[NetworkManager] Sent LN_PUBKEY to peer={}", peerId);
    }

    /**
     * Returns our local node identity string.
     * @return 
     */
    @Override
    public String getLocalId() {
        return localId;
    }

    /**
     * Returns the underlying blockchain node object.
     * @return 
     */
    //@Override
    //public BTCHDMBWalletApp getBlockChainNode() {
    //    return blockChainNode;
    //}

    /**
     * Blocks until an invoice payload arrives from the merchant.
     * Used by the customer side after connecting.
     * @return 
     * @throws java.lang.InterruptedException
     */
    @Override
    public InvoicePayload waitForInvoice() throws InterruptedException {
        AppLogger.info("[NetworkManager] Waiting for invoice from merchant...");

        InvoicePayload payload = invoiceQueue.poll(
            MESSAGE_WAIT_MS, TimeUnit.MILLISECONDS);

        if (payload == null) {
            throw new InterruptedException(
                "Timeout waiting for invoice after " + MESSAGE_WAIT_MS + " ms");
        }

        String paymentHashStr = HexUtils.bytesToHex(payload.getPaymentHash());
        AppLogger.info("[NetworkManager] Invoice received: hash={} amount={} sat",
            paymentHashStr.substring(0, 12),
            payload.getAmountSat());

        return payload;
    }

    // ─────────────────────────────────────────────────────────────────
    // Channel ID helper (used by customer setup)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Blocks until the merchant sends the channel ID notification.
     * Call this on the customer side after connecting to the merchant.
     * @return 
     * @throws java.lang.InterruptedException
     */
    public String waitForChannelId() throws InterruptedException {
        AppLogger.info("[NetworkManager] Waiting for channel ID from merchant...");
        String channelId = channelIdQueue.poll(MESSAGE_WAIT_MS, TimeUnit.MILLISECONDS);
        if (channelId == null) {
            throw new InterruptedException(
                "Timeout waiting for channel ID after " + MESSAGE_WAIT_MS + " ms");
        }
        AppLogger.info("[NetworkManager] Channel ID received: {}", channelId);
        return channelId;
    }

    // ─────────────────────────────────────────────────────────────────
    // Parse helpers
    // ─────────────────────────────────────────────────────────────────

    private InvoicePayload parseInvoicePayload(String json) {
        // minimal JSON parse without external library
        // production: use Jackson or Gson
        try {
            String hash        = extractJson(json, "paymentHash");
            long   amountSat   = Long.parseLong(extractJson(json, "amountSat"));
            long   expiryEpoch = Long.parseLong(extractJson(json, "expiryEpoch"));
            String channelId   = extractJson(json, "channelId");

            byte[] hashBytes = HexUtils.hexToBytes(hash);
            return new InvoicePayload(
                hashBytes, amountSat, "Payment for goods",
                expiryEpoch, "", channelId);

        } catch (NumberFormatException e) {
            AppLogger.error("[NetworkManager] Failed to parse invoice payload: {}",
                e.getMessage());
            return null;
        }
    }

    /** Extracts a string value from a flat JSON object without a library. */
    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":";
        int    start  = json.indexOf(search);
        if (start < 0) throw new NoSuchElementException("Key not found: " + key);
        start += search.length();
        // skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.charAt(start) == '"') {
            // string value
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        } else {
            // numeric value
            int end = start;
            while (end < json.length()
                    && json.charAt(end) != ','
                    && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }
}