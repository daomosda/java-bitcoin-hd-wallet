package com.bitcoin.hdwallet.apisserver;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.entrypoint.BTCHDWallet;
import com.bitcoin.hdwallet.lightningnetwork.PaymentException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ApisServer {

    private final HttpServer server;
    private final BTCHDWallet hdWallet;  // your main app / controller object

    public ApisServer(int port, BTCHDWallet hdWallet) throws IOException {
        this.hdWallet = hdWallet;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // Map URLs to handlers
        server.createContext("/wallet/info", new WalletInfoHandler());
        server.createContext("/fee/estimate", new FeeEstimateHandler());
        server.createContext("/wallet/send", new SendCoinsHandler());
        server.createContext("/blocks/mine", new MineBlocksHandler());
        server.createContext("/merchant/receive", new MerchantReceiveHandler());
        server.createContext("/customer/pay", new CustomerPaymentHandler());
        server.createContext("/delegation", new DelegationHandler());
        server.createContext("/utxo/consolidate", new ConsolidateChangeHandler());

        server.setExecutor(null);
    }

    public void start() {
        server.start();
        AppLogger.info("[ApisServer] HTTP API server started on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        AppLogger.info("[ApisServer] HTTP API server stopped.");
    }

    // -------- Handlers mapping to your switch cases --------

    // GET /wallet/info
    //
    // Returns current wallet state as JSON, e.g.:
    //
    // 200 OK
    // {
    //   "balanceBtc": "1.23456789",
    //   "unconfirmedBalanceBtc": "0.01000000",
    //   "numUtxos": 12,
    //   "nextReceiveAddress": "...",
    //   "nextChangeAddress": "..."
    // }
    class WalletInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                // You may want to return JSON instead of printing to console.
                String info = hdWallet.getWalletInfoAsJson();  // implement this wrapper
                sendResponse(exchange, 200, info);
            } catch (Exception ex) {
                System.getLogger(ApisServer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }

    // GET /fee/estimate
    //
    // Returns fee rate estimate (e.g. for next blocks) as JSON, e.g.:
    //
    // 200 OK
    // {
    //   "feerateSatPerVb": 10,
    //   "targetBlocks": 6
    // }
    class FeeEstimateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                String feeJson = hdWallet.getFeeEstimateAsJson();  // wrapper around showFeeEstimate()
                sendResponse(exchange, 200, feeJson);
            } catch (BitcoinRpcException ex) {
                System.getLogger(ApisServer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }
    
    // POST /wallet/send  { "toAddress": "...", "amountBtc": "0.1", "targetBlocks": 6 }
    class SendCoinsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                ApiSendRequest req = ApiSendRequest.fromJson(body); // parse JSON
                SendCoinsResult result = hdWallet.sendCoinsApi(
                    req.getToAddress(),
                    req.getAmountBtc(),
                    req.getTargetBlocks()
                );
                String json = "{"
                    + "\"status\":\"ok\","
                    + "\"firstTxid\":\"" + result.getFirstTxid() + "\","
                    + "\"secondTxid\":\"" + result.getSecondTxid() + "\""
                    + "}";
                sendResponse(exchange, 200, json);
            } catch (Exception e) {
                AppLogger.error("[ApisServer] sendCoinsApi failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    // POST /merchant/receive  { "capacitySat": 1_000_000, "chainHeight": 200 }
    class MerchantReceiveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                ApiReceivePaymentRequest req = ApiReceivePaymentRequest.fromJson(body);
                ReceivePaymentResult result =
                    hdWallet.receivePaymentApisWrapper(req.getCapacitySat(), req.getChainHeight());
                String json = "{"
                    + "\"status\":\"ok\","
                    + "\"channelId\":\"" + result.getChannelId() + "\""
                    + "}";
                sendResponse(exchange, 200, json);
            } catch (IOException e) {
                AppLogger.error("[ApisServer] receivePaymentAPIs failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (PaymentException ex) {
                System.getLogger(ApisServer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }
    
    // POST /customer/pay { "channelId": "xyz", "pubKeyHex": "..." }
    class CustomerPaymentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                ApiCustomerPaymentRequest req = ApiCustomerPaymentRequest.fromJson(body);
                byte[] pubKeyBytes = req.getPubKeyBytes(); // decode hex to bytes
                hdWallet.makeCustomerPaymentApi(req.getChannelId(), pubKeyBytes);
                sendResponse(exchange, 200, "{\"status\":\"ok\"}");
            } catch (Exception e) {
                AppLogger.error("[ApisServer] makeCustomerPaymentApi failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // POST /blocks/mine
    // Body: { "numBlocks": 1, "miningAddress": "bcrt1q..." }
    //
    // numBlocks  – optional; default 1 if omitted.
    // miningAddress – optional; if omitted, wallet chooses a mining address.
    //
    // 200 OK
    // { "status": "ok", "blocksMined": 1, "newHeight": 201 }
    class MineBlocksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                int blocksMined = hdWallet.mineBlocks(); // wrapper around mineBlocks()
                sendResponse(exchange, 200, "{\"status\":\"ok\",\"blocksMined\":" + blocksMined + "}");
            } catch (IOException e) {
                AppLogger.error("[ApisServer] mineBlocks failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ex) {
                System.getLogger(ApisServer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }

    // POST /delegation
    // Body: { "workerId": 1 }
    //
    // workerId – integer ID of the worker to delegate to (e.g. 1 or 2).
    //
    // 200 OK
    // { "status": "ok", "workerId": 1 }
    class DelegationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                ApiDelegationRequest req = ApiDelegationRequest.fromJson(body); // contains chosenWorker
                hdWallet.handleDelegation(req.getWorkerId());
                sendResponse(exchange, 200, "{\"status\":\"ok\"}");
            } catch (IOException e) {
                AppLogger.error("[ApisServer] handleDelegation failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // POST /utxo/consolidate
    // Body: { }
    //
    // No parameters required; consolidates eligible change UTXOs into a single
    // spendable UTXO, if any exist.
    //
    // 200 OK (consolidation performed)
    // {
    //   "status": "ok",
    //   "txid": "abcd1234..."
    // }
    //
    // 200 OK (nothing to do)
    // {
    //   "status": "noop",
    //   "message": "No change UTXOs to consolidate"
    // }
    class ConsolidateChangeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                String txid = hdWallet.consolidateChangeApi(); // wrap convertChangeToSpendableUtxo()
                if (txid != null) {
                    sendResponse(exchange, 200,
                            "{\"status\":\"ok\",\"txid\":\"" + txid + "\"}");
                } else {
                    sendResponse(exchange, 200,
                            "{\"status\":\"noop\",\"message\":\"No change UTXOs to consolidate\"}");
                }
            } catch (IOException e) {
                AppLogger.error("[ApisServer] consolidateChange failed: {}", e.getMessage(), e);
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ex) {
                System.getLogger(ApisServer.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }

    // Utility method
    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    // DTO for API result
    public static class SendCoinsResult {
        private final String firstTxid;
        private final String secondTxid;

        public SendCoinsResult(String firstTxid, String secondTxid) {
            this.firstTxid = firstTxid;
            this.secondTxid = secondTxid;
        }
        public String getFirstTxid()  { return firstTxid; }
        public String getSecondTxid() { return secondTxid; }
    }
       
    public static class CustomerPaymentRequest {
        private final String channelId;
        private final byte[] pubKeyBytes;

        public CustomerPaymentRequest(String channelId, byte[] pubKeyBytes) {
            this.channelId = channelId;
            this.pubKeyBytes = pubKeyBytes;
        }

        public String getChannelId() { return channelId; }
        public byte[] getPubKeyBytes() { return pubKeyBytes; }
    }
    
    public static class ReceivePaymentResult {
        private final String channelId;

        public ReceivePaymentResult(String channelId) {
            this.channelId = channelId;
        }

        public String getChannelId() {
            return channelId;
        }
    }
}
