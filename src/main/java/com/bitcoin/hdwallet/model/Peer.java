package com.bitcoin.hdwallet.model;

/**
 *
 * @author DAOMOSDA
 */

/**
 * Represents one connected P2P peer.
 */
public final class Peer {

    private final String  id;          // unique peer identifier
    private       String  pubKey;      // LN compressed pubkey hex (may arrive late)
    private final String  address;     // IP:port
    private final boolean merchant;   // true if this peer is the merchant node

    // message sending is delegated to your existing P2P socket layer
    private final MessageSender sender;

    public Peer(String        id,
                String        pubKey,
                String        address,
                boolean       merchant,
                MessageSender sender) {
        this.id       = id;
        this.pubKey   = pubKey;
        this.address  = address;
        this.merchant = merchant;
        this.sender   = sender;
    }

    public String  getId()        { return id;       }
    public String  getPubKey()    { return pubKey;   }
    public String  getAddress()   { return address;  }
    public boolean isMerchant()   { return merchant; }

    public void setPubKey(String pubKey) {
        this.pubKey = pubKey;
    }

    /**
     * Sends a typed Message to this peer over the P2P socket.
     */
    public void sendMessage(Message message) {
        sender.send(id, message);
    }

    @Override
    public String toString() {
        return "Peer{id=" + id +
               " addr=" + address +
               " merchant=" + merchant + "}";
    }

    // ── MessageSender — implemented by your P2P socket layer ─────────

    @FunctionalInterface
    public interface MessageSender {
        void send(String peerId, Message message);
    }
}