package com.bitcoin.hdwallet.core;

import com.bitcoin.hdwallet.cacheUtil.WordFileStore;
import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chainindexing.BlockDataMapper;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import static com.bitcoin.hdwallet.core.NetworkException.Reason.CONNECTION_REFUSED;
import static com.bitcoin.hdwallet.core.NetworkException.Reason.HOST_UNREACHABLE;
import static com.bitcoin.hdwallet.core.NetworkException.Reason.SSL_ERROR;
import static com.bitcoin.hdwallet.core.NetworkException.Reason.TIMEOUT;
import com.bitcoin.hdwallet.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.*;

/**
 *
 * @author DAOMOSDA
 */

public class BitcoinRpcClient {

    //private static final ObjectMapper MAPPER = new ObjectMapper()
    //        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static String rpcUrl;
    private final String rpcUser;
    private final String rpcPassword;
    private static HttpClient httpClient;
    private  String walletName;
    
    private URI endpoint;
    private final String basicAuth;
     
    private static final int  CONNECT_TIMEOUT_SECONDS = 10;
    private static final int  REQUEST_TIMEOUT_SECONDS = 30;
    private static final int  MAX_RETRY_ATTEMPTS      = 3;
    private static final long RETRY_DELAY_MS          = 2_000L;
    
    private static final String RPC_URL = "http://127.0.0.1:18443/";
    private static final String RPC_USER = "olamosda";
    private static final String RPC_PASSWORD = "cona.btc-Dao_5MoS7";
    
    private String RPC_HOST;  //   = "127.0.0.1";
    private int  RPC_PORT ;  //   = 18443;

    public BitcoinRpcClient(String host, int port, String rpcUser, String rpcPassword, String walletName) {
        String path = (walletName == null || walletName.isEmpty()) ? "" : "/wallet/" + walletName;
        this.endpoint = URI.create(String.format("http://%s:%d%s", host, port, path));
        AppLogger.info("BCJSONRpchttp: " + this.endpoint.toString());
        //this.rpcUrl = "http://" + host + ":" + port + "/wallet/" + (walletName != null ? walletName : "");
        this.rpcUrl = String.format("http://%s:%d%s", host, port, "");
        this.httpClient = HttpClient.newHttpClient();
        this.RPC_HOST = host;
        this.RPC_PORT = port;
        this.rpcUser = rpcUser;
        this.rpcPassword = rpcPassword;
        this.walletName = walletName;
        this.basicAuth = Base64.getEncoder()
                .encodeToString((this.rpcUser + ":" + this.rpcPassword).getBytes(StandardCharsets.UTF_8));
    }
    
    public BitcoinRpcClient(String host, int port, String rpcUser, String rpcPassword) {
        this(host, port, rpcUser, rpcPassword, null);
    }
       
    private static String basicAuth(String username, String password) {
        String auth = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
    }
    
    
    private BitcoinRpcClient(String fullUrl, String rpcUser, String rpcPassword, boolean alreadyFullUrl) {
        rpcUrl = fullUrl;
        this.rpcUser = rpcUser;
        this.rpcPassword = rpcPassword;
        this.basicAuth = Base64.getEncoder()
                .encodeToString((this.rpcUser + ":" + this.rpcPassword).getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Return a new BitcoinRpcClient scoped to the given wallet
     * @param walletName
     * @return 
     */
    public BitcoinRpcClient forWallet(String walletName) {
        //this.rpcUrl = String.format("http://%s:%d%s", host, port, "");
        String fullUrl = rpcUrl + "/wallet/" + walletName;
        return new BitcoinRpcClient(fullUrl, this.rpcUser, this.rpcPassword, true);
    }

     /**
     * Execute raw RPC call
     * @param method
     * @param params
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    /*
    public static Object executeRpc(String method, Object... params) throws BitcoinRpcException {
        try {
            JSONArray paramsArray = new JSONArray();
            if (params != null) {
                for (Object param : params) {

                    if (param == null) {
                        paramsArray.put(JSONObject.NULL);

                    } else if (param instanceof JSONObject || param instanceof JSONArray) {
                        paramsArray.put(param);

                    } else if (param instanceof Number || param instanceof Boolean || param instanceof String) {
                        paramsArray.put(param);

                    } else if (param instanceof List) {
                        paramsArray.put(new JSONArray((List<?>) param));

                    } else if (param instanceof Map) {
                        paramsArray.put(new JSONObject((Map<?, ?>) param));

                    } else {
                        // fallback
                        paramsArray.put(param.toString());
                    }
                }
            }

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "1.0"); // IMPORTANT
            request.put("id", "java-rpc");
            request.put("method", method);
            request.put("params", paramsArray);            
              
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth(RPC_USER, RPC_PASSWORD)) // THE FIX
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BitcoinRpcException(
                        "HTTP " + response.statusCode() + ": " + response.body()
                );
            }

            //String body = response.body();
            //if (body == null || body.isBlank()) {
            //    throw new BitcoinRpcException("Empty RPC response");
            //}
            //AppLogger.debug("[BitcoinRpcClient => executeRpc()] RPC raw response: {}", body);
            
            JSONObject responseJson = new JSONObject(response.body());

            // Handle RPC error
            if (!responseJson.isNull("error") && responseJson.get("error") != JSONObject.NULL) {
                JSONObject err = responseJson.getJSONObject("error");

                throw new BitcoinRpcException(
                        err.optInt("code", -1),
                        err.optString("message", "Unknown error")
                );
            }

            return responseJson.get("result");

        } catch (IOException | InterruptedException e) {
            throw new BitcoinRpcException("RPC communication error", e);
        } catch (BitcoinRpcException | JSONException e) {
            throw new BitcoinRpcException("RPC processing error", e);
        }
    }
    */
    
    public static Object executeRpc(String method, Object... params) throws BitcoinRpcException {
        try {
            JSONArray paramsArray = new JSONArray();
            if (params != null) {
                for (Object param : params) {

                    if (param == null) {
                        paramsArray.put(JSONObject.NULL);

                    } else if (param instanceof JSONObject || param instanceof JSONArray) {
                        paramsArray.put(param);

                    } else if (param instanceof String) {
                        // SMART FIX: Check if the string is actually a JSON object or array.
                        // This prevents extra quotes around raw JSON payloads (e.g. for importdescriptors).
                        String strParam = ((String) param).trim();
                        if (strParam.startsWith("[")) {
                            paramsArray.put(new JSONArray(strParam));
                        } else if (strParam.startsWith("{")) {
                            paramsArray.put(new JSONObject(strParam));
                        } else {
                            // Standard string parameter
                            paramsArray.put(strParam);
                        }

                    } else if (param instanceof Number || param instanceof Boolean) {
                        paramsArray.put(param);

                    } else if (param instanceof List) {
                        paramsArray.put(new JSONArray((List<?>) param));

                    } else if (param instanceof Map) {
                        paramsArray.put(new JSONObject((Map<?, ?>) param));

                    } else {
                        // fallback
                        paramsArray.put(param.toString());
                    }
                }
            }

            JSONObject request = new JSONObject();
            request.put("jsonrpc", "1.0"); // IMPORTANT
            request.put("id", "java-rpc");
            request.put("method", method);
            request.put("params", paramsArray);            

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth(RPC_USER, RPC_PASSWORD))
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BitcoinRpcException(
                        "HTTP " + response.statusCode() + ": " + response.body()
                );
            }

            JSONObject responseJson = new JSONObject(response.body());

            // Handle RPC error
            if (!responseJson.isNull("error") && responseJson.get("error") != JSONObject.NULL) {
                JSONObject err = responseJson.getJSONObject("error");

                throw new BitcoinRpcException(
                        err.optInt("code", -1),
                        err.optString("message", "Unknown error")
                );
            }

            return responseJson.get("result");

        } catch (IOException | InterruptedException e) {
            throw new BitcoinRpcException("RPC communication error", e);
        } catch (BitcoinRpcException | JSONException e) {
            throw new BitcoinRpcException("RPC processing error", e);
        }
    }
    
    public void ensureWalletExistsAndLoaded(String walletName) throws BitcoinRpcException, Exception {
        // 1. Already loaded?
        if (isWalletLoaded(walletName)) {
            AppLogger.info("[Wallet] '" + walletName + "' already loaded.");
            return;
        }

        // 2. Exists on disk?
        if (walletExistsOnDisk(walletName)) {
            // Just load it — safe and expected
            loadWallet(walletName);
        } else {
            // Only now: create it for the first time
            createWatchOnlyDescriptorWallet(walletName);
        }
    }
    
    public void ensureWatchOnlyWalletExistsAndLoaded(String walletName) throws BitcoinRpcException {
        if (isWalletLoaded(walletName)) return;
        if (walletExistsOnDisk(walletName)) {
            loadWallet(walletName);
        } else {
            createWatchOnlyDescriptorWallet(walletName);
        }
    }    
       
    // 1) Is the wallet currently loaded in memory?
    public boolean isWalletLoaded(String walletName) throws BitcoinRpcException {
        Object result = executeRpc("listwallets");  // result is a JSON array of wallet names

        if (!(result instanceof JSONArray wallets)) {
            throw new BitcoinRpcException("listwallets: unexpected result type: " + result.getClass());
        }

        for (int i = 0; i < wallets.length(); i++) {
            String loadedWallet = wallets.getString(i);
            if (walletName.equals(loadedWallet)) {
                return true;
            }
        }

        return false;
    }

    // 2) Does wallet exist on disk (in regtest/wallets) regardless of loaded state?
    public boolean walletExistsOnDisk(String walletName) throws BitcoinRpcException {  
        Object result = executeRpc("listwalletdir"); // result is an object: {"wallets":[...]}  

        if (!(result instanceof JSONObject obj)) {
            throw new BitcoinRpcException("listwalletdir: unexpected result type: " + result.getClass());
        }

        JSONArray wallets = obj.getJSONArray("wallets");
        for (int i = 0; i < wallets.length(); i++) {
            JSONObject w = wallets.getJSONObject(i);
            String name = w.getString("name");
            if (walletName.equals(name)) {
                return true;
            }
        }

        return false;
    }
    
    public void createWatchOnlyDescriptorWallet(String walletName) throws BitcoinRpcException {
        AppLogger.info("[Wallet] Creating watch-only descriptor wallet '{}' ...", walletName);

        // createwallet "name" disable_private_keys blank passphrase avoid_reuse descriptors load_on_startup
        Object result = executeRpc("createwallet",
            walletName,
            true,   // disable_private_keys = true  -> watch-only
            true,   // blank = true (no default descriptors)
            "",     // passphrase
            false,  // avoid_reuse
            true,   // descriptors = true
            true    // load_on_startup
        );

        if (!(result instanceof JSONObject obj)) {
            throw new BitcoinRpcException("createwallet: unexpected result type: " + result.getClass());
        }

        System.out.printf("[Wallet] Created watch-only descriptor wallet '{}' (warning: {})",
                       obj.optString("name", walletName),
                       obj.optString("warning", ""));
    }

    /*
    private void createWallet(String name) throws Exception {
        AppLogger.info("[Wallet] Creating wallet '" + name + "' ...");
        Object result = executeRpc("createwallet",
                           walletName,
                           true,  // disable_private_keys
                           false,  // blank
                           "",     // passphrase
                           false,  // avoid_reuse
                           true,   // descriptors
                           true);  // load_on_startup

        if (!(result instanceof JSONObject res)) {
            throw new BitcoinRpcException("loadwallet: unexpected result type: " + result.getClass());
        }
        
        System.out.printf("[Wallet] Loaded wallet '{}' (warning: {})",
                       res.optString("name", name),
                       res.optString("warning", ""));

        AppLogger.info("[Wallet] Created wallet '" + name + "'");
    }
    */
    // Mine blocks on regtest using generatetoaddress
    public List<String> generateToAddress(int nblocks, String address) throws BitcoinRpcException, IOException {
        Object result = executeRpc("generatetoaddress", nblocks, address);
        
        WordFileStore wordFileStore = (WordFileStore) CachedWalletMnemMap.getObject("wordFileStore");
        if (result instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) result;
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                hashes.add(arr.getString(i));
            }
            wordFileStore.appendWord(address);
            return hashes;
        } else if (result instanceof JsonNode && ((JsonNode) result).isArray()) {
            JsonNode arr = (JsonNode) result;
            List<String> hashes = new ArrayList<>();
            for (JsonNode el : arr) {
                hashes.add(el.asText());
            }
            wordFileStore.appendWord(address);
            return hashes;
        } else {
            throw new BitcoinRpcException("Unexpected result type for generatetoaddress: " + result.getClass());
        }
    }
    
    /*
    public List<String> generateToAddress(int nBlocks, String address) throws IOException, BitcoinRpcException {
        //List<Object> params = new ArrayList<>();
        //params.add(nBlocks);
        //params.add(address);

        JsonNode response = (JsonNode) executeRpc("generatetoaddress",  nBlocks, address);
        List<String> hashes = new ArrayList<>();
        if (response.isArray()) {
            for (JsonNode h : response) {
                hashes.add(h.asText());
            }
        }
        return hashes;
    }
    */

    private void loadWallet(String name) throws BitcoinRpcException {
        AppLogger.info("[Wallet] Loading wallet '" + name + "' ...");

        // Call executeRpc with the filename as a plain String param
        Object result = executeRpc("loadwallet", name);

        // According to Core, result is an object like: {"name": "...", "warning": "..."}
        if (!(result instanceof JSONObject res)) {
            throw new BitcoinRpcException("loadwallet: unexpected result type: " + result.getClass());
        }

        System.out.printf("[Wallet] Loaded wallet '{}' (warning: {})",
                       res.optString("name", name),
                       res.optString("warning", ""));
    }
    
    /**
     * Returns a new address from the connected Bitcoin Core wallet.
     *
     * Equivalent to: bitcoin-cli getnewaddress
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public String getNewAddress() throws IOException, BitcoinRpcException {
        Object res = executeRpc("getnewaddress");  // no params

        // If executeRpc already returns a String address:
        if (res instanceof String s) {
            return s;
        }

        // If sometimes you still return JsonNode:
        if (res instanceof JsonNode node) {
            return node.asText();
        }

        throw new IllegalStateException(
            "Unexpected getnewaddress result type: " + (res == null ? "null" : res.getClass())
        );
    }
    
    /**
     * Optional: labeled address, or specific type ("bech32", "p2sh-segwit", "legacy").
     * @param label
     * @param addressType
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public String getNewAddress(String label, String addressType) throws IOException, BitcoinRpcException {
        List<Object> params = new ArrayList<>();
        if (label != null) {
            params.add(label);
        } else {
            params.add(""); // default empty label[web:315]
        }
        if (addressType != null) {
            params.add(addressType); // must be one of "legacy", "p2sh-segwit", "bech32"[web:309][web:315]
        }
        JsonNode result = (JsonNode) executeRpc("getnewaddress", params);
        return result.asText();
    }
    
    // Optional: Helper to list loaded wallets to check status
    public List<String> listWallets() throws BitcoinRpcException {
        JSONObject response = (JSONObject) executeRpc("listwallets", new ArrayList<>());
        return (List<String>) response.get("result"); // Assuming your JSON lib handles this, or parse manually
    }
    // ==================== RPC Methods ====================

    /**
     * listunspent - Returns array of unspent transaction outputs
     * 
     * @param minConfirmations Minimum confirmations (inclusive)
     * @param maxConfirmations Maximum confirmations (inclusive)
     * @param addresses Optional list of addresses to filter
     * @return List of unspent outputs
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public List<Utxo> listUnspent(
        int minConfirmations,
        int maxConfirmations,
        List<String> addresses
    ) throws BitcoinRpcException {

        Object result;

        if (addresses != null && !addresses.isEmpty()) {
            result = executeRpc("listunspent", minConfirmations, maxConfirmations, addresses);
        } else {
            result = executeRpc("listunspent", minConfirmations, maxConfirmations);
        }
   
        if (!(result instanceof JSONArray utxoArry)) {
            throw new RuntimeException("listunspent: unexpected result: " + result.getClass());
        }
                
        //JSONArray array = (JSONArray) result;
        List<Utxo> utxos = new ArrayList<>();
        
        for (int i = 0; i < utxoArry.length(); i++) {
            /*
            JSONObject obj = utxoArry.getJSONObject(i);
            Utxo u = new Utxo();
            u.setTxid(obj.getString("txid"));
            u.setVout(obj.getInt("vout"));
            u.setHeight(obj.getInt("height"));
            u.setCoinbase(obj.getBoolean("coinbase"));
            u.setConfirmations(obj.getInt("confirmations"));
            u.setAddress(obj.optString("address", null));
            u.setScriptPubHex(obj.getString("scriptPubKey"));                        
            u.setAmount(BigDecimal.valueOf(obj.getDouble("amount") / 100_000_000.0));
            u.setAmount(BigDecimal.valueOf(obj.getDouble("amount")));           
            u.setSpendable(obj.optBoolean("spendable", true));
            u.setSafe(obj.optBoolean("safe", true));
            u.setDescriptor(obj.optString("desc", ""));
            u.setSolvable(obj.optBoolean("solvable", true));
            */
            JSONObject obj = utxoArry.getJSONObject(i);

            Utxo u = new Utxo();

            u.setTxid(obj.getString("txid"));
            u.setVout(obj.getInt("vout"));
            u.setHeight(obj.optInt("height", 0));
            u.setCoinbase(obj.optBoolean("coinbase", false));
            u.setConfirmations(obj.optInt("confirmations", 0));

            u.setAddress(obj.optString("address", null));
            u.setScriptPubHex(obj.getString("scriptPubKey"));
            // ⚠️ FIX: do NOT divide here twice
            long amountSat = (long) (obj.getDouble("amount") * 100_000_000L);
            u.setAmountSat(amountSat);
            u.setAmount(BigDecimal.valueOf(amountSat, 8));
            u.setSpendable(obj.optBoolean("spendable", true));
            u.setSafe(obj.optBoolean("safe", true));
            u.setDescriptor(obj.optString("desc", ""));
            u.setSolvable(obj.optBoolean("solvable", true));
            u.setSpent(obj.optBoolean("spent", false));
            u.setSpentByTxid(obj.optString("spent_by_txid", null));
            u.setSpentAtHeight(
                obj.has("spent_at_height") ? obj.getInt("spent_at_height") : null
            );
            
            
            utxos.add(u);
        }
        
        /*
        Utxo(
            String txid,
            int vout,
            int height,
            boolean coinbase,
            int confirmations,
            String address,
            String scriptPubHex,
            BigDecimal amount,
            long amountSat,
            boolean isSpendable,
            boolean safe,
            String descriptor,
            boolean solvable)
        */

        return utxos;
    }
    
    public BlockData getBlockDataVerbose(String blockHash) throws Exception {
        JSONObject resp = (JSONObject) executeRpc("getblock", blockHash, 2);
        return BlockDataMapper.mapBlock(resp);
    }
       
    /** Fetches a block with full transaction detail (verbosity=2).
     * @param blockHash
     * @return 
     * @throws java.lang.Exception */
    //public RpcBlock getRpcBlockVerbose(String blockHash) throws Exception {
    //    Object raw = executeRpc("getblock", blockHash, 2);  // verbosity=2
    //    return BlockParser.parse(raw);
    //}
    
    public int getCoreTipHeight() throws BitcoinRpcException {
        JSONObject info = (JSONObject) executeRpc("getblockchaininfo");
        return info.getInt("blocks");
    }

    /**
     * listunspent without address filter
     * @param minConfirmations
     * @param maxConfirmations
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public List<Utxo> listUnspent(int minConfirmations, int maxConfirmations) throws BitcoinRpcException {
        return listUnspent(minConfirmations, maxConfirmations, null);
    }  
    
    public CreateWalletResult createWallet(CreateWalletParams p)
        throws BitcoinRpcException {

        Object result = executeRpc(
            "createwallet",
            p.getWalletName(),          // string ✅
            p.isDisablePrivateKeys(),   // boolean
            p.isBlank(),                // boolean
            p.getPassphrase(),          // string
            p.isAvoidReuse(),           // boolean
            p.isDescriptors(),          // boolean
            p.isLoadOnStartup(),        // boolean
            p.getExternalSigner()       // boolean or null
        );

        JSONObject res = (JSONObject) result;

        CreateWalletResult out = new CreateWalletResult();
        out.setName(res.optString("name"));
        out.setWarning(res.optString("warning"));

        return out;
    }

    /**
     * importdescriptors - Import descriptors
     * 
     * @param descriptors List of descriptor objects to import
     * @return List of import results
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public List<ImportDescriptorResult> importDescriptors(
        List<DescriptorRequest> descriptors
    ) throws BitcoinRpcException {

        JSONArray reqArray = new JSONArray();

        for (DescriptorRequest d : descriptors) {
            JSONObject obj = new JSONObject();
            obj.put("desc", d.getDesc());
            obj.put("active", d.isActive());
            obj.put("timestamp", d.getTimestamp());

            reqArray.put(obj);
        }

        Object result = executeRpc("importdescriptors", reqArray);

        JSONArray arr = (JSONArray) result;
        List<ImportDescriptorResult> out = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            ImportDescriptorResult r = new ImportDescriptorResult();
            r.setSuccess(o.getBoolean("success"));
            r.setError(o.toMap());   //optJSONObject("error"));

            out.add(r);
        }

        return out;
    }   
    
    public String getDescriptorWithChecksum(String rawDesc) throws Exception {

        Object res = executeRpc("getdescriptorinfo", rawDesc);

        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException(
                    "getdescriptorinfo: unexpected result " + res.getClass()
            );
        }

        return obj.getString("descriptor");
    }
    
    /**
     * importmulti - Import addresses/scripts with options
     * 
     * @param requests List of import requests
     * @param options Import options
     * @return List of import results
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public List<ImportMultiResult> importMulti(
        List<ImportMultiRequest> requests,
        ImportMultiOptions options
    ) throws BitcoinRpcException {

        JSONArray reqArray = new JSONArray();

        for (ImportMultiRequest r : requests) {
            JSONObject obj = new JSONObject();
            
            JSONObject scriptPubKey = new JSONObject();
            scriptPubKey.put("address", r.getScriptPubKey());
            obj.put("scriptPubKey", scriptPubKey);
            //obj.put("scriptPubKey", Map.of("address", r.getAddress()));
            obj.put("timestamp", r.getTimestamp());
            obj.put("watchonly", r.isWatchonly());

            reqArray.put(obj);
        }

        JSONObject opts = new JSONObject();
        opts.put("rescan", options.isRescan());

        Object result = executeRpc("importmulti", reqArray, opts);

        JSONArray arr = (JSONArray) result;
        List<ImportMultiResult> out = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            ImportMultiResult r = new ImportMultiResult();
            r.setSuccess(o.getBoolean("success"));
            r.setError(o.toMap());   //optJSONObject("error"));

            out.add(r);
        }

        return out;
    }

    /**
     * importmulti with default options
     * @param requests
     * @return 
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public List<ImportMultiResult> importMulti(List<ImportMultiRequest> requests) throws BitcoinRpcException {
        return importMulti(requests, new ImportMultiOptions());
    }

    /**
     * importaddress - Adds an address or script to the wallet without the associated private key
     * 
     * @param address The Bitcoin address or script to import
     * @param label An optional label
     * @param rescan Rescan the blockchain after import
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public void importAddress(String address, String label, boolean rescan)
        throws BitcoinRpcException {

        executeRpc("importaddress", address, label, rescan);
    }
    
    /*
    public int getBlockCount() throws IOException, BitcoinRpcException {
        Object result = executeRpc("getblockcount", Collections.emptyList()); // RPC: getblockcount()[web:346][web:343][web:360]

        if (result instanceof JsonNode node) {
            return node.asInt();  // JsonNode -> int[web:319][web:321]
        }
        if (result instanceof Number num) {
            return num.intValue(); // if executeRpc already returns a Number
        }
        throw new IllegalStateException("Unexpected getblockcount result type: " + result);
    }
    */
    
    /*
    public void syncFromCore(ChainIndexRepository chainStore) throws Exception {
        int lastHeight = chainStore.getLastHeightOrMinusOne();  // or syncDao

        ensureOnMainChain(lastHeight);

        // refresh after possible rollback
        lastHeight = chainStore.getLastHeightOrMinusOne();

        int tip = getCoreTipHeight();

        for (int height = lastHeight + 1; height <= tip; height++) {
            String blockHash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);
            JSONObject blockJson = (JSONObject) BitcoinRpcClient.executeRpc("getblock", blockHash, 2);

            chainStore.processBlock(height, blockJson);
            
            chainStore.updateLastHeight(height);
        }
    }
    */

    // Add to your existing BitcoinRpcClient

    /** Fetches a block with full transaction detail (verbosity=2).
     * @throws java.lang.Exception */
    //public RpcBlock getBlockVerbose2(String blockHash) throws Exception {
    //    Object raw = executeRpc("getblock", blockHash, 2);  // verbosity=2
    //    return BlockParser.parse(raw);
    //}
    public int getChainTipHeight() throws Exception {
        // getblockcount returns the tip height (genesis = 0). [web:429][web:430][web:433]
        Object res = executeRpc("getblockcount");
        if (res == null) {
            throw new IllegalStateException("getblockcount returned null");
        }

        // Depending on your RPC client, this may be Integer, Long, or Number
        if (res instanceof Number num) {
            return num.intValue();
        }
        // If your wrapper returns a String:
        if (res instanceof String s) {
            return Integer.parseInt(s);
        }

        throw new IllegalStateException("Unexpected getblockcount result type: " + res.getClass());
    }


    /** Returns the current block count from Core.
     * @return 
     * @throws java.lang.Exception */
    public int getBlockCount() throws Exception {
        Object raw = executeRpc("getblockcount");
        if (raw instanceof org.json.JSONObject obj) return obj.getInt("result");
        if (raw instanceof Number n)               return n.intValue();
        // some RPC clients unwrap the result automatically
        return Integer.parseInt(raw.toString().trim());
    }

    /** Returns the block hash at a given height.
     * @param height
     * @return 
     * @throws java.lang.Exception */
    public String getBlockHash(int height) throws Exception { // already called elsewhere
        // already called elsewhere
        // use the specific call:
        Object res = executeRpc("getblockhash", height);
        if (res instanceof org.json.JSONObject obj) return obj.getString("result");
        return res.toString().trim().replace("\"", "");
    }

    /** Thin chain-tip record used by ReorgDetector. */
    public record ChainTip(int height, String hash) {}

    public ChainTip getChainTip() throws Exception {
        Object raw = executeRpc("getblockchaininfo");
        if (raw instanceof org.json.JSONObject obj) {
            return new ChainTip(obj.getInt("blocks"), obj.getString("bestblockhash"));
        }
        throw new IllegalStateException("Unexpected getblockchaininfo response: " + raw.getClass());
    }
    
    public List<String> listAllWalletAddresses() throws IOException, BitcoinRpcException {
        List<Object> params = new ArrayList<>();
        params.add(0);      // minconf
        params.add(true);   // include_empty
        params.add(true);   // include_watchonly[web:366][web:368]

        JsonNode arr = (JsonNode) executeRpc("listreceivedbyaddress", params);
        List<String> all = new ArrayList<>();
        for (JsonNode entry : arr) {
            all.add(entry.get("address").asText());
        }
        return all;
    }

    public List<String> listUsedWalletAddresses() throws IOException, BitcoinRpcException {
        List<Object> params = new ArrayList<>();
        params.add(0);
        params.add(false);  // include_empty = false[web:366][web:368]
        params.add(true);

        JsonNode arr = (JsonNode) executeRpc("listreceivedbyaddress", params);
        List<String> used = new ArrayList<>();
        for (JsonNode entry : arr) {
            used.add(entry.get("address").asText());
        }
        return used;
    }
    
    /**
     * getnetworkinfo - Returns network information
     * 
     * @return Network information
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public NetworkInfo getNetworkInfo() throws BitcoinRpcException {

        Object result = executeRpc("getnetworkinfo");

        if (!(result instanceof JSONObject)) {
            throw new BitcoinRpcException("Unexpected result type");
        }

        JSONObject obj = (JSONObject) result;

        NetworkInfo info = new NetworkInfo();

        info.setVersion(Integer.toString(obj.getInt("version")));
        // REMOVED Integer.parseInt because subversion is a String (e.g., "/Satoshi:28.1.0/")
        info.setSubversion(obj.getString("subversion")); 
        info.setProtocolversion(Integer.toString(obj.getInt("protocolversion")));
        info.setConnections(obj.getInt("connections"));
        info.setRelayfee(obj.getDouble("relayfee"));

        return info;
    }
    
    public BlockchainInfo getBlockchainInfo() throws Exception {

        // 🔴 Use node RPC, not wallet RPC
        Object res = executeRpc("getblockchaininfo");

        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException(
                    "getblockchaininfo: unexpected result " + res.getClass()
            );
        }

        return BlockchainInfo.fromJson(obj);
        
        /*
        return new BlockchainInfo(
                obj.getString("chain"),
                obj.getInt("blocks"),
                obj.getInt("headers"),
                obj.getBigDecimal("difficulty"),
                obj.getLong("size_on_disk"),
                obj.getBoolean("initialblockdownload")
        );
        */
    }
    
    public boolean isValidCoreAddress(String address) throws BitcoinRpcException {
        Object res = executeRpc("validateaddress", address);
        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException("validateaddress: unexpected result " + res.getClass());
        }
        boolean valid = obj.getBoolean("isvalid");
        System.out.println("[validateaddress] addr=" + address + " isvalid=" + valid + " raw=" + obj);
        return valid;
    }
    
    public void ensureOnMainChain(ChainIndexRepository chainStore) throws Exception {
        Connection conn = chainStore.DBConn().getConnection();
        int localHeight;
        try {
            chainStore.DBConn().beginTransaction(conn);
        localHeight = chainStore.getLastHeightOrMinusOne(conn);
        if (localHeight < 0) {
            return; // nothing indexed yet
        }
        // Get Core's hash at localHeight
        String coreHash = (String) BitcoinRpcClient.executeRpc("getblockhash", localHeight);
        String localHash = chainStore.getHashAtHeight(conn, localHeight);

        if (localHash == null) {
            // Our DB is missing this block; safest is to treat as needing full resync from 0
            chainStore.updateLastHeight(conn, -1);
            return;
        }
        
        if (coreHash.equals(localHash)) {
            // No divergence at tip
            return;
        }

        // Divergence detected: roll back until we find a common ancestor
        rollbackToCommonAncestor(conn, chainStore, localHeight);
        chainStore.DBConn().commit(conn);
        } catch (SQLException e) {
            chainStore.DBConn().rollback(conn);
            throw e;
        }
        chainStore.DBConn().releaseThreadConnection();
    }
    
    private void rollbackToCommonAncestor(Connection conn, ChainIndexRepository chainStore, int localHeight) throws Exception {
        int height = localHeight;

        while (height >= 0) {
            String localHash = chainStore.getHashAtHeight(conn, height);
            if (localHash == null) {
                height--;
                continue;
            }

            String coreHash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);

            if (coreHash.equals(localHash)) {
                // Found common ancestor at 'height'
                int deleteFrom = height + 1;

                //chainStore.DBConn().setAutoCommit(false);
                //try {
                chainStore.deleteBlocksFromHeight(conn, deleteFrom);
                chainStore.updateLastHeight(conn, height);
                //chainStore.DBConn().commit();
                //} catch (SQLException e) {
                //    chainStore.DBConn().rollback();
                //    throw e;
                //}

                System.out.printf("Reorg: rolled back to height {} (hash={})", height, localHash);
                return;
            }

            height--;
        }

        // No common ancestor found (extremely unlikely in practice if you've always synced from Core).
        // Force full resync:
        //chainStore.DBConn().setAutoCommit(false);
        //try {
        chainStore.deleteBlocksFromHeight(conn,0);
        chainStore.updateLastHeight(conn, -1);
        //chainStore.DBConn().commit(conn);
        //} catch (SQLException e) {
        //    chainStore.DBConn().rollback();
        //    throw e;
        //} 

        AppLogger.warn("Reorg: no common ancestor found, full reindex required");
    } 
    /**
     * Sends the HTTP request, retrying on network errors up to MAX_RETRY_ATTEMPTS.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request)  
            throws NetworkException, IOException {

        int       attempt   = 0;
        Throwable lastCause = null;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            System.out.printf("HTTP attempt %d/%d → %s%n",
                attempt, MAX_RETRY_ATTEMPTS, endpoint);

            try {
                return httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

            } catch (ConnectException e) {
                lastCause = e;
                System.err.printf("ConnectException on attempt %d/%d: %s%n",
                        attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
                waitBeforeRetry(attempt);

            } catch (HttpTimeoutException e) {
                lastCause = e;
                System.err.printf("Timeout on attempt %d/%d: %s%n",
                        attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
                waitBeforeRetry(attempt);

            } catch (IOException e) {
                // Covers UnknownHostException, SSLException, etc.
                lastCause = e;
                System.err.printf("IOException on attempt %d/%d [%s]: %s%n",
                        attempt, MAX_RETRY_ATTEMPTS,
                        e.getClass().getSimpleName(), e.getMessage());
                waitBeforeRetry(attempt);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // always restore interrupt flag
                throw new NetworkException(
                    "RPC request interrupted",
                    RPC_HOST, RPC_PORT,
                    NetworkException.Reason.UNKNOWN, e
                );
            }
        }

        // All attempts exhausted
        throw buildNetworkException(lastCause);
    }

    // ── NetworkException Handler ──────────────────────────────────────────────

    /**
     * Logs a descriptive message for each NetworkException reason.
     * Called in public methods that cannot propagate NetworkException.
     */
    private void handleNetworkException(NetworkException e, String method) {
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        switch (e.getReason()) {

            case CONNECTION_REFUSED -> {
                AppLogger.error("✗ Bitcoind is not running or wrong port.");
                AppLogger.error("  Host    : " + e.getHost());
                AppLogger.error("  Port    : " + e.getPort());
                AppLogger.error("  Method  : " + method);
                AppLogger.error("  Action  : Start bitcoind and retry.");
            }
            case HOST_UNREACHABLE -> {
                AppLogger.error("✗ Host not found: " + e.getHost());
                AppLogger.error("  Check RPC_HOST configuration.");
            }
            case TIMEOUT -> {
                AppLogger.error("✗ Bitcoind did not respond within "
                        + REQUEST_TIMEOUT_SECONDS + "s.");
                AppLogger.error("  Method  : " + method);
                AppLogger.error("  Action  : Check bitcoind is not overloaded.");
            }
            case SSL_ERROR -> {
                AppLogger.error("✗ SSL/TLS error. Check node certificate.");
            }
            default -> {
                AppLogger.error("✗ Unknown network error: " + e.getMessage());
            }
        }
        AppLogger.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void waitBeforeRetry(int attempt) {
        if (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                AppLogger.info("Waiting " + RETRY_DELAY_MS + "ms before retry...");
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private NetworkException buildNetworkException(Throwable cause) {
        if (cause instanceof ConnectException) {
            return new NetworkException(
                "Bitcoind refused connection after " + MAX_RETRY_ATTEMPTS
                + " attempts — is bitcoind running at " + RPC_HOST + ":" + RPC_PORT + "?",
                RPC_HOST, RPC_PORT,
                NetworkException.Reason.CONNECTION_REFUSED, cause
            );
        }
        if (cause instanceof UnknownHostException) {
            return new NetworkException(
                "Host not found: " + RPC_HOST + " — check RPC_HOST.",
                RPC_HOST, RPC_PORT,
                NetworkException.Reason.HOST_UNREACHABLE, cause
            );
        }
        if (cause instanceof HttpTimeoutException) {
            return new NetworkException(
                "Bitcoind at " + RPC_HOST + ":" + RPC_PORT
                + " did not respond within " + REQUEST_TIMEOUT_SECONDS + "s.",
                RPC_HOST, RPC_PORT,
                NetworkException.Reason.TIMEOUT, cause
            );
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return new NetworkException(
                "SSL/TLS error connecting to " + RPC_HOST + ":" + RPC_PORT,
                RPC_HOST, RPC_PORT,
                NetworkException.Reason.SSL_ERROR, cause
            );
        }
        return new NetworkException(
            "Unexpected network error: "
            + (cause != null ? cause.getMessage() : "unknown"),
            RPC_HOST, RPC_PORT,
            NetworkException.Reason.UNKNOWN, cause
        );
    }
        
    public ListDescriptorsResult listDescriptors()
        throws Exception {

        JSONObject result =
                (JSONObject) executeRpc(
                        "listdescriptors"
                );

        JSONArray arr =
                result.getJSONArray("descriptors");

        List<ListDescriptorsResult.DescriptorInfo> list =
                new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {

            JSONObject o =
                    arr.getJSONObject(i);

            String desc =
                    o.getString("desc");

            long timestamp =
                    o.optLong(
                            "timestamp",
                            0
                    );

            boolean active =
                    o.optBoolean(
                            "active",
                            false
                    );

            boolean internal =
                    o.optBoolean(
                            "internal",
                            false
                    );

            int[] range = null;

            if (o.has("range")) {

                JSONArray r =
                        o.getJSONArray("range");

                range = new int[]{
                        r.getInt(0),
                        r.getInt(1)
                };
            }

            boolean hasNext =
                    o.has("next");

            int next =
                    hasNext
                            ? o.getInt("next")
                            : -1;

            list.add(
                    new ListDescriptorsResult
                            .DescriptorInfo(
                                    desc,
                                    timestamp,
                                    active,
                                    internal,
                                    range,
                                    hasNext,
                                    next
                            )
            );
        }

        return new ListDescriptorsResult(
                list
        );
    }
    
    /**
     * Estimates smart fee in sat/vB for confirmation within targetBlocks
     * RPC: estimatesmartfee conf_target [estimate_mode]
     * 
     * @param targetBlocks Confirmation target in blocks (1-1008)
     * @return Fee rate in sat/vB (satoshis per virtual byte)
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public long estimateSmartFeeSatPerVb(int targetBlocks) throws IOException, BitcoinRpcException {
        if (targetBlocks < 1 || targetBlocks > 1008) {
            throw new IllegalArgumentException("targetBlocks must be between 1 and 1008");
        }
        
        JsonNode result = (JsonNode) executeRpc("estimatesmartfee", targetBlocks, "economical");
        
        if (result.has("errors") && !result.get("errors").isEmpty()) {
            String errorMsg = result.get("errors").get(0).asText();
            throw new RuntimeException("Fee estimation failed: " + errorMsg);
        }
        
        if (!result.has("feerate")) {
            throw new RuntimeException("No fee estimate available for target: " + targetBlocks);
        }
        
        // feerate is in BTC/kvB, convert to sat/vB: 1 BTC/kvB = 100 sat/vB
        long feerateBtcPerKvB = result.get("feerate").asLong();
        return feerateBtcPerKvB * 100;
    }
    
    /**
     * Gets confirmed balance in BTC (transactions with ≥1 confirmation)
     * RPC: getbalance "*" minconf include_watchonly
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public double getConfirmedBalanceBtc() throws IOException, BitcoinRpcException {
        return (Double) executeRpc("getbalance", "*", 1, false);
    }
    
    /**
     * Gets unconfirmed balance in BTC (pending transactions)
     * RPC: Total (minconf=0) minus confirmed (minconf=1)
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public double getUnconfirmedBalanceBtc() throws IOException, BitcoinRpcException {
        double total = (Double) executeRpc("getbalance", "*", 0, false);
        double confirmed = getConfirmedBalanceBtc();
        return total - confirmed;
    }
    
    /**
     * Gets the count of UTXOs in the wallet
     * RPC: listunspent minconf maxconf
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    public int getUtxoCount() throws IOException, BitcoinRpcException {
        Object result = executeRpc("listunspent", 1, 9999999);
                
        if (!(result instanceof JSONArray utxos)) {
            throw new RuntimeException("listunspent: unexpected result: " + result.getClass());
        }
                
        //JSONArray array = (JSONArray) result;
        return utxos.length();
    }
    
    /**
     * Handles incoming API receive request for Bitcoin payments
     * Generates address, creates payment record, returns response
     * @param req
     * @return 
     * @throws java.io.IOException
     * @throws com.bitcoin.hdwallet.core.BitcoinRpcException
     */
    /*
    public PaymentResponse handleApiReceive(ApiRequest req) throws IOException, BitcoinRpcException {
        if (req == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        
        String label = req.getLabel() != null ? req.getLabel() : "";
        String address;
        
        if (req.getAddress() != null && !req.getAddress().isEmpty()) {
            address = req.getAddress();
        } else {
            JsonNode addressResult = (JsonNode) executeRpc("getnewaddress", label, "bech32");
            address = addressResult.asText();
        }
        
        PaymentResponse response = new PaymentResponse();
        response.setAddress(address);
        response.setAmountBtc(req.getAmountBtc());
        response.setLabel(label);
        response.setTimestamp(System.currentTimeMillis());
        response.setStatus("pending");
        
        return response;
    }
    */
    // ============================================================================
    // HELPER CLASSES
    // ============================================================================    
        
    public static class PaymentResponse {
        private String address;
        private double amountBtc;
        private String label;
        private long timestamp;
        private String status;
        
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public double getAmountBtc() { return amountBtc; }
        public void setAmountBtc(double amountBtc) { this.amountBtc = amountBtc; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
    
    // ==================== Data Classes ====================
    public static class CreateWalletParams {
        private String walletName;
        private boolean disablePrivateKeys = false;
        private boolean blank = false;
        private String passphrase;
        private boolean avoidReuse = false;
        private boolean descriptors = true;
        private boolean loadOnStartup = false;
        private String externalSigner;

        public CreateWalletParams(String walletName) {
            this.walletName = walletName;
        }

        // Getters and Setters
        public String getWalletName() { return walletName; }
        public CreateWalletParams setWalletName(String walletName) { 
            this.walletName = walletName; 
            return this; 
        }
        public boolean isDisablePrivateKeys() { return disablePrivateKeys; }
        public CreateWalletParams setDisablePrivateKeys(boolean disablePrivateKeys) { 
            this.disablePrivateKeys = disablePrivateKeys; 
            return this; 
        }
        public boolean isBlank() { return blank; }
        public CreateWalletParams setBlank(boolean blank) { 
            this.blank = blank; 
            return this; 
        }
        public String getPassphrase() { return passphrase; }
        public CreateWalletParams setPassphrase(String passphrase) { 
            this.passphrase = passphrase; 
            return this; 
        }
        public boolean isAvoidReuse() { return avoidReuse; }
        public CreateWalletParams setAvoidReuse(boolean avoidReuse) { 
            this.avoidReuse = avoidReuse; 
            return this; 
        }
        public boolean isDescriptors() { return descriptors; }
        public CreateWalletParams setDescriptors(boolean descriptors) { 
            this.descriptors = descriptors; 
            return this; 
        }
        public boolean isLoadOnStartup() { return loadOnStartup; }
        public CreateWalletParams setLoadOnStartup(boolean loadOnStartup) { 
            this.loadOnStartup = loadOnStartup; 
            return this; 
        }
        public String getExternalSigner() { return externalSigner; }
        public CreateWalletParams setExternalSigner(String externalSigner) { 
            this.externalSigner = externalSigner; 
            return this; 
        }
    }

    public static class CreateWalletResult {
        private String name;
        private String warning;
        
        public CreateWalletResult createWallet(String walletName)
            throws BitcoinRpcException {

            Object result = executeRpc("createwallet", walletName);

            JSONObject res = (JSONObject) result;

            CreateWalletResult out = new CreateWalletResult();
            out.setName(res.optString("name"));
            out.setWarning(res.optString("warning"));

            return out;
        }

        // Getters and Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }

        @Override
        public String toString() {
            return "CreateWalletResult{name='" + name + "', warning='" + warning + "'}";
        }
    }

    public static class DescriptorRequest {
        private String desc;
        private boolean active = true;
        private String label;
        private int timestamp = 0; // "now" = 0, specific timestamp
        private boolean internal = false;
        private int range = -1; // -1 means no range
        private int nextIndex = 0;
        private boolean watchonly = false;
        
        public DescriptorRequest() {
        }
        
        public DescriptorRequest(String desc, boolean active, boolean internal, int timestamp) {
            this.desc = desc;
            this.active = active;
            this.internal = internal;
            this.timestamp = timestamp;
        }

        // Getters and Setters
        public String getDesc() { return desc; }
        
        public DescriptorRequest setDesc(String desc) { 
            this.desc = desc; 
            return this; 
        }
        public boolean isActive() { return active; }
        
        public DescriptorRequest setActive(boolean active) { 
            this.active = active; 
            return this; 
        }
        public String getLabel() { return label; }
        
        public DescriptorRequest setLabel(String label) { 
            this.label = label; 
            return this; 
        }
        public int getTimestamp() { return timestamp; }
        
        public DescriptorRequest setTimestamp(int timestamp) { 
            this.timestamp = timestamp; 
            return this; 
        }
        
        public boolean isInternal() { return internal; }
        
        public DescriptorRequest setInternal(boolean internal) { 
            this.internal = internal; 
            return this; 
        }
        
        public int getRange() { return range; }
        
        public DescriptorRequest setRange(int range) { 
            this.range = range; 
            return this; 
        }
        
        public int getNextIndex() { return nextIndex; }
        
        public DescriptorRequest setNextIndex(int nextIndex) { 
            this.nextIndex = nextIndex; 
            return this; 
        }
        
        public boolean isWatchonly() { return watchonly; }
        
        public DescriptorRequest setWatchonly(boolean watchonly) { 
            this.watchonly = watchonly; 
            return this; 
        }

        public static DescriptorRequest forDescriptor(String descriptor) {
            return new DescriptorRequest().setDesc(descriptor);
        }

        public static DescriptorRequest forDescriptor(String descriptor, String label) {
            return new DescriptorRequest().setDesc(descriptor).setLabel(label);
        }
    }

    public static class ImportDescriptorResult {
        private boolean success;
        private Map<String, Object> warnings;
        private Map<String, Object> error;

        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Map<String, Object> getWarnings() { return warnings; }
        public void setWarnings(Map<String, Object> warnings) { this.warnings = warnings; }
        public Map<String, Object> getError() { return error; }
        public void setError(Map<String, Object> error) { this.error = error; }

        @Override
        public String toString() {
            return "ImportDescriptorResult{success=" + success + ", warnings=" + warnings + ", error=" + error + "}";
        }
    }

    public static class ImportMultiRequest {
        private String scriptPubKey;
        private Map<String, Object> scriptPubKeyDescriptor;
        private String pubkeys;
        private List<String> keys;
        private String label;
        private boolean internal = false;
        private boolean watchonly = false;
        private int timestamp = 0; // "now" = 0

        // Getters and Setters
        public String getScriptPubKey() { return scriptPubKey; }
        public ImportMultiRequest setScriptPubKey(String scriptPubKey) { 
            this.scriptPubKey = scriptPubKey; 
            return this; 
        }
        public Map<String, Object> getScriptPubKeyDescriptor() { return scriptPubKeyDescriptor; }
        public ImportMultiRequest setScriptPubKeyDescriptor(Map<String, Object> descriptor) { 
            this.scriptPubKeyDescriptor = descriptor; 
            return this; 
        }
        public String getPubkeys() { return pubkeys; }
        public ImportMultiRequest setPubkeys(String pubkeys) { 
            this.pubkeys = pubkeys; 
            return this; 
        }
        public List<String> getKeys() { return keys; }
        public ImportMultiRequest setKeys(List<String> keys) { 
            this.keys = keys; 
            return this; 
        }
        public String getLabel() { return label; }
        public ImportMultiRequest setLabel(String label) { 
            this.label = label; 
            return this; 
        }
        public boolean isInternal() { return internal; }
        public ImportMultiRequest setInternal(boolean internal) { 
            this.internal = internal; 
            return this; 
        }
        public boolean isWatchonly() { return watchonly; }
        public ImportMultiRequest setWatchonly(boolean watchonly) { 
            this.watchonly = watchonly; 
            return this; 
        }
        public int getTimestamp() { return timestamp; }
        public ImportMultiRequest setTimestamp(int timestamp) { 
            this.timestamp = timestamp; 
            return this; 
        }

        public static ImportMultiRequest forAddress(String address) {
            ImportMultiRequest request = new ImportMultiRequest();
            request.setScriptPubKey(address);
            return request;
        }

        public static ImportMultiRequest forAddress(String address, String label) {
            ImportMultiRequest request = forAddress(address);
            request.setLabel(label);
            return request;
        }
    }

    public static class ImportMultiOptions {
        private boolean rescan = true;

        // Getters and Setters
        public boolean isRescan() { return rescan; }
        public ImportMultiOptions setRescan(boolean rescan) { 
            this.rescan = rescan; 
            return this; 
        }
    }

    public static class ImportMultiResult {
        private boolean success;
        private Map<String, Object> warnings;
        private Map<String, Object> error;

        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Map<String, Object> getWarnings() { return warnings; }
        public void setWarnings(Map<String, Object> warnings) { this.warnings = warnings; }
        public Map<String, Object> getError() { return error; }
        public void setError(Map<String, Object> error) { this.error = error; }

        @Override
        public String toString() {
            return "ImportMultiResult{success=" + success + ", warnings=" + warnings + ", error=" + error + "}";
        }
    }

    public static class NetworkInfo {
        private String version;
        private String subversion;
        private String protocolversion;
        private String localservices;
        private int localservicesnames;
        private boolean networkactive;
        private int connections;
        private List<Object> networks;
        private double relayfee;
        private double incrementalfee;
        private List<String> localaddresses;
        private Map<String, Object> warnings;

        // Getters and Setters
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getSubversion() { return subversion; }
        public void setSubversion(String subversion) { this.subversion = subversion; }
        public String getProtocolversion() { return protocolversion; }
        public void setProtocolversion(String protocolversion) { this.protocolversion = protocolversion; }
        public boolean isNetworkactive() { return networkactive; }
        public void setNetworkactive(boolean networkactive) { this.networkactive = networkactive; }
        public int getConnections() { return connections; }
        public void setConnections(int connections) { this.connections = connections; }
        public double getRelayfee() { return relayfee; }
        public void setRelayfee(double relayfee) { this.relayfee = relayfee; }
        public double getIncrementalfee() { return incrementalfee; }
        public void setIncrementalfee(double incrementalfee) { this.incrementalfee = incrementalfee; }

        @Override
        public String toString() {
            return "NetworkInfo{" +
                    "version='" + version + '\'' +
                    ", protocolversion='" + protocolversion + '\'' +
                    ", networkactive=" + networkactive +
                    ", connections=" + connections +
                    ", relayfee=" + relayfee +
                    '}';
        }
    }
    
    public static final class PsbtCreateResult {
        private final String psbtBase64;
        private final BigDecimal feeBtc;
        private final int changePos;

        public PsbtCreateResult(String psbtBase64, BigDecimal feeBtc, int changePos) {
            this.psbtBase64 = psbtBase64;
            this.feeBtc = feeBtc;
            this.changePos = changePos;
        }

        public String getPsbtBase64() { return psbtBase64; }
        public BigDecimal getFeeBtc() { return feeBtc; }
        public int getChangePos() { return changePos; }
    }

    public PsbtCreateResult walletCreateFundedPsbt(
        List<JSONObject> inputs,
        JSONObject outputs,
        int locktime,
        boolean includeWatching,
        String changeAddress
    ) throws BitcoinRpcException {

        JSONObject options = new JSONObject();

        options.put("includeWatching", includeWatching);

        // IMPORTANT:
        // Use explicit change address from YOUR HD wallet
        if (changeAddress != null && !changeAddress.isBlank()) {
            options.put("changeAddress", changeAddress);
        }

        // Descriptor-native SegWit
        //options.put("change_type", "bech32");

        options.put("replaceable", false);

        options.put("add_inputs",
                inputs == null || inputs.isEmpty());

        // sat/vB
        options.put("fee_rate", 1);

        Object res = executeRpc(
                "walletcreatefundedpsbt",
                inputs != null
                        ? inputs
                        : new JSONArray(),
                outputs,
                locktime,
                options,
                true
        );

        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException(
                    "walletcreatefundedpsbt: unexpected result "
                            + res.getClass()
            );
        }

        String psbt   = obj.getString("psbt");
        BigDecimal fee = obj.getBigDecimal("fee");
        int changepos  = obj.getInt("changepos");

        return new PsbtCreateResult(psbt, fee, changepos);
    }    
}
