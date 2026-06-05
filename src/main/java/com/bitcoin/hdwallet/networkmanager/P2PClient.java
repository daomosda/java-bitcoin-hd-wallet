package com.bitcoin.hdwallet.networkmanager;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.model.Message;
import com.bitcoin.hdwallet.model.Peer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages outbound TCP connections to remote peers.
 *
 * When your node needs to connect to a known peer (e.g. the merchant
 * connecting to the customer's P2P port at startup), use this class.
 *
 * Also calls onMessageReceived() for messages arriving on outbound connections
 * — the direction of the TCP connection does not matter for message routing.
 */
public class P2PClient {

    private final NetworkManagerImpl networkManager;
    private final ExecutorService    threadPool;

    public P2PClient(NetworkManagerImpl networkManager) {
        this.networkManager = networkManager;
        this.threadPool     = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "p2p-client");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Connects to a remote peer and starts reading messages from them.
     *
     * @param host       remote host e.g. "127.0.0.1"
     * @param port       remote P2P port e.g. 8334
     * @param isMerchant true if this peer is the merchant node
     * @throws java.io.IOException
     */
    public void connectTo(String host, int port, boolean isMerchant)
            throws IOException {
        Socket socket = new Socket(host, port);
        AppLogger.info("[P2PClient] Connected to {}:{}", host, port);

        String peerId = "peer-" + host + "-" + port;

        // register the peer with a send lambda
        Peer peer = new Peer(
            peerId,
            null,         // pubKey unknown until LN_PUBKEY message arrives
            host + ":" + port,
            isMerchant,
            (id, message) -> sendToSocket(socket, message)
        );
        networkManager.addPeer(peer);

        // start reading inbound messages on this connection
        threadPool.submit(() -> readLoop(socket, peerId));
    }

    /**
     * Reads messages from an outbound connection.
     * Calls onMessageReceived() for each — same as the server-side reader.
     */
    private void readLoop(Socket socket, String peerId) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                AppLogger.info("[P2PClient] ← {} : {}", peerId, line);
                try {
                    Message message = MessageSerializer.deserialize(line);

                    // ── onMessageReceived called here too ─────────────
                    networkManager.onMessageReceived(message);

                } catch (Exception e) {
                    AppLogger.error("[P2PClient] Bad message from {}: {}",
                        peerId, e.getMessage());
                }
            }

        } catch (IOException e) {
            AppLogger.error("[P2PClient] Disconnected from {}: {}", peerId, e.getMessage());
        } finally {
            networkManager.removePeer(peerId);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void sendToSocket(Socket socket, Message message) {
        try {
            String     json   = MessageSerializer.serialize(message);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println(json);
        } catch (IOException e) {
            AppLogger.error("[P2PClient] Send failed: {}", e.getMessage());
        }
    }
}