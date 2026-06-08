package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

/**
 * Thrown when the wallet cannot reach the Bitcoin network.
 * Wraps java.net.ConnectException with Bitcoin-specific context.
 */
public class NetworkException extends Exception {

    public enum Reason {
        CONNECTION_REFUSED,    // node is down or wrong port
        HOST_UNREACHABLE,      // DNS failure or no route to host
        TIMEOUT,               // connected but no response in time
        SSL_ERROR,             // TLS/certificate issue
        UNKNOWN
    }

    private final String host;
    private final int    port;
    private final Reason reason;

    public NetworkException(String message, String host, int port, Reason reason) {
        super(message);
        this.host   = host;
        this.port   = port;
        this.reason = reason;
    }

    public NetworkException(String message, String host, int port,
                            Reason reason, Throwable cause) {
        super(message, cause);
        this.host   = host;
        this.port   = port;
        this.reason = reason;
    }

    public String getHost()   { return host; }
    public int    getPort()   { return port; }
    public Reason getReason() { return reason; }

    @Override
    public String toString() {
        return String.format(
            "NetworkException{reason=%s, host='%s', port=%d, message='%s'}",
            reason, host, port, getMessage()
        );
    }
}