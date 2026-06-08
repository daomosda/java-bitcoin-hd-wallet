package com.bitcoin.hdwallet.entrypoint;

/**
 *
 * @author DAOMOSDA
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class BitcoinCorePinger {

    private final String host;
    private final int port;
    private final String rpcUser;
    private final String rpcPassword;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public BitcoinCorePinger(String host, int port, String rpcUser, String rpcPassword) {
        this.host = host;
        this.port = port;
        this.rpcUser = rpcUser;
        this.rpcPassword = rpcPassword;
    }

    public boolean isCoreRunningByPing() {
        // JSON-RPC payload as a text block (Java 15+)
        String payload = """
            {"jsonrpc":"1.0","id":"java-health","method":"ping","params":[]}
            """;

        try {
            String rpcUrl = "http://" + host + ":" + port + "/";

            String auth = rpcUser + ":" + rpcPassword;
            String encodedAuth = Base64.getEncoder()
                    .encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + encodedAuth)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // HTTP 200 means Core is alive and accepted the RPC
            return response.statusCode() == 200;

        } catch (IOException | InterruptedException e) {
            // Connection refused, timeout, etc.
            return false;
        }
    }
}