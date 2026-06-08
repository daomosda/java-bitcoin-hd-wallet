package com.bitcoin.hdwallet.networkmanager;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.model.Message;
import com.bitcoin.hdwallet.model.Peer;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;


/**
 * Listens for inbound TCP connections from peers.
 * For every new connection, spins up a reader thread that:
 *   1. Deserializes each incoming line into a Message.
 *   2. Calls networkManager.onMessageReceived(message).
 *
 * This is the ONLY place that calls onMessageReceived().
 * It is the single entry point from the network into the payment logic.
 */
public class P2PServer {

    private final int                port;
    private final NetworkManagerImpl networkManager;
    private final ExecutorService    threadPool;
    private       ServerSocket       serverSocket;
    private volatile boolean         running = false;

    public P2PServer(int port, NetworkManagerImpl networkManager) {
        this.port           = port;
        this.networkManager = networkManager;
        this.threadPool     = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "p2p-conn");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Start / Stop ──────────────────────────────────────────────────

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running      = true;

        Thread acceptThread = new Thread(this::acceptLoop, "p2p-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        AppLogger.info("[P2PServer] Listening on port {}", port);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
        AppLogger.info("[P2PServer] Stopped");
    }

    // ── Accept loop ───────────────────────────────────────────────────

    /**
     * Blocks waiting for new TCP connections.
     * Each accepted connection gets its own reader thread.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                AppLogger.info("[P2PServer] New connection from {}",
                    socket.getRemoteSocketAddress());

                // spin up a reader for this connection
                threadPool.submit(() -> handleConnection(socket));

            } catch (IOException e) {
                if (running) {
                    AppLogger.error("[P2PServer] Accept error: {}", e.getMessage());
                }
            }
        }
    }

    // ── Per-connection handler ────────────────────────────────────────

    /**
     * Reads messages from one peer connection in a loop.
     *
     * Protocol (simple line-based for clarity):
     *   Each message is one JSON line terminated by '\n'.
     *   Format: {"type":"LN_PUBKEY","payload":"03abc...","senderId":"node-1"}
     *
     * In production use a proper framing protocol (length prefix, etc.)
     */
    private void handleConnection(Socket socket) {
        String remoteAddr = socket.getRemoteSocketAddress().toString();
        String peerId     = "peer-" + remoteAddr.replace("/", "")
                                                  .replace(":", "-");

        // register peer — socket writer lambda handles outbound sends
        Peer peer = new Peer(
            peerId,
            null,       // pubKey arrives via LN_PUBKEY message
            remoteAddr,
            false,      // merchant flag set later if needed
            (id, message) -> sendToSocket(socket, message)
        );
        networkManager.addPeer(peer);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null && running) {
                AppLogger.info("[P2PServer] ← {} : {}", peerId, line);
                try {
                    // ── deserialize JSON line into Message ────────────
                    Message message = MessageSerializer.deserialize(line);

                    // ── THIS IS WHERE onMessageReceived IS CALLED ─────
                    //    Every inbound message from every peer goes here.
                    networkManager.onMessageReceived(message);

                } catch (Exception e) {
                    AppLogger.error("[P2PServer] Failed to handle message from {}: {}",
                        peerId, e.getMessage());
                }
            }

        } catch (IOException e) {
            AppLogger.error("[P2PServer] Connection closed: {} ({})",
                peerId, e.getMessage());
        } finally {
            networkManager.removePeer(peerId);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Outbound send ─────────────────────────────────────────────────

    /**
     * Serializes and writes a Message to the socket output stream.
     * Called by Peer.sendMessage() via the lambda registered in handleConnection.
     */
    private void sendToSocket(Socket socket, Message message) {
        try {
            String json = MessageSerializer.serialize(message);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println(json);
            AppLogger.info("[P2PServer] → {} : type={}", socket.getRemoteSocketAddress(),
                message.getType());
        } catch (IOException e) {
            AppLogger.error("[P2PServer] Send failed: {}", e.getMessage());
        }
    }
}