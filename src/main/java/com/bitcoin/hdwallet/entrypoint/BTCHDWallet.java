package com.bitcoin.hdwallet.entrypoint;

import com.bitcoin.hdwallet.apisserver.ApisServer;
import com.bitcoin.hdwallet.apisserver.ApisServer.ReceivePaymentResult;
import com.bitcoin.hdwallet.apisserver.ApisServer.SendCoinsResult;
import com.bitcoin.hdwallet.cacheUtil.CLDAddress;
import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import com.bitcoin.hdwallet.cacheUtil.WordFileStore;
import com.bitcoin.hdwallet.chainindexing.ChainIndexer;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.AppNWKConfig;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.core.BlockchainInfo;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.core.FeeEstimator;
import com.bitcoin.hdwallet.core.ListDescriptorsResult;
import com.bitcoin.hdwallet.core.UtxoSyncManager;
import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.crypto.SegWitAddress;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.MAINNET;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.REGTEST;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.TESTNET;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.database.HDKeyDatabase;
import com.bitcoin.hdwallet.inputcontrol.SharedMonitor;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import com.bitcoin.hdwallet.keymanagement.HdWalletService;
import com.bitcoin.hdwallet.lightningnetwork.CustomerPaymentHandler;
import com.bitcoin.hdwallet.lightningnetwork.LightningPaymentConstants;
import com.bitcoin.hdwallet.lightningnetwork.LightningService;
import com.bitcoin.hdwallet.lightningnetwork.LightningServiceImpl;
import com.bitcoin.hdwallet.lightningnetwork.MerchantPaymentHandler;
import com.bitcoin.hdwallet.lightningnetwork.PaymentException;
import com.bitcoin.hdwallet.model.HDKey;
import com.bitcoin.hdwallet.model.Invoice;
import com.bitcoin.hdwallet.networkmanager.NetworkManagerImpl;
import com.bitcoin.hdwallet.networkmanager.P2PClient;
import com.bitcoin.hdwallet.networkmanager.P2PServer;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.repository.HDKeyRepository;
import com.bitcoin.hdwallet.tranxcontrol.CoinSelector;
import com.bitcoin.hdwallet.tranxcontrol.FundingGuard;
import com.bitcoin.hdwallet.tranxcontrol.OwnSigner;
import com.bitcoin.hdwallet.tranxcontrol.WalletAnalyzer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author CONALDES
 */

public class BTCHDWallet extends Thread {
    
    private FeeEstimator feeEstimator;
    private static UtxoSyncManager utxoSync;
    private ChainIndexRepository indexStoreHandle;
    private static ChainIndexer chainIndexer;
    
    private static Bip32HDWallet masterKey;
    
    private  byte[] pubKeyBytes;  
  
    private static final Set<String> addressCache = ConcurrentHashMap.newKeySet();
    
    private static HdAddressManager addrMgr;
   
    private String WATCHONLY_WALLET_NAME;
    private static SegWitAddress.Network network;
    
    private static BitcoinRpcClient nodeRpc;      // base RPC (no wallet suffix)
    private static BitcoinRpcClient walletRpc;    // /wallet/btcnode_watchonly
    
    // ── new lightning fields ──────────────────────────────────────────
    private MerchantPaymentHandler merchantHandler;
    private CustomerPaymentHandler customerHandler;
    private LightningService lightningService;
    private ApisServer apisServer;
    
    private OwnSigner ownSigner;
    
    private final SharedMonitor monitor;
    private boolean running = true;
    
    private static Set<String> addrCache;   
    private String node_usage;

    public BTCHDWallet() {
        this.monitor = new SharedMonitor();  

        try {
            
            Security.addProvider(new BouncyCastleProvider());
            
            node_usage = getNodeUsage(); 
            // ─────────────────────────────────────────────
            // 1. CONFIG
            // ─────────────────────────────────────────────
            
            String LOG_DIR = ConfigFilePaths.addressDir();
            Path LOG_FILE = ConfigFilePaths.addressContrlLog();             
            
            String ADDRFILE = ConfigFilePaths.addressFileLog();   
            WordFileStore wordFileStore = new WordFileStore(ADDRFILE);
            CachedWalletMnemMap.cachedNewObject("wordFileStore", wordFileStore);
            
            AppNWKConfig config = AppNWKConfig.getInstance();
            network = AppNWKConfig.getInstance().getNetwork();   
            CachedWalletMnemMap.cachedNewObject("network", network);            

            // ─────────────────────────────────────────────
            // 2. DATABASE INIT
            // ─────────────────────────────────────────────
            String chainPath = ConfigFilePaths.chainDBPath();   

            ChainDatabase sqLteDb = new ChainDatabase(chainPath);
            sqLteDb.runMigrations();

            indexStoreHandle = new ChainIndexRepository(sqLteDb);
            
            final String host = config.getRpcAllowIp();  
            final int port = config.getRpcPort();  
            final String rpcUser = config.getRpcUser();  
            final String rpcPassword = config.getRpcPassword(); 
            // ─────────────────────────────────────────────
            // 3. RPC + WALLET SETUP
            // ─────────────────────────────────────────────
            nodeRpc = new BitcoinRpcClient(host, port, rpcUser, rpcPassword);
            WATCHONLY_WALLET_NAME = AppNWKConfig.getInstance().getWatchOnlyWalletName();   
            
            // 1. Network info
            BitcoinCorePinger bitcoinCorePinger = new BitcoinCorePinger(host, port, rpcUser, rpcPassword);
            if (bitcoinCorePinger.isCoreRunningByPing()) {
                if (isRegtestConfig()) {
                    System.out.println("Bitcoin Core is running in REGTEST mode.");
                    nodeRpc.ensureWatchOnlyWalletExistsAndLoaded(WATCHONLY_WALLET_NAME);
                    walletRpc = nodeRpc.forWallet(WATCHONLY_WALLET_NAME);
                } else {
                    System.out.println("Bitcoin Core is NOT in REGTEST mode.");
                    System.out.println("Re-configure Bitcoin core to run in REGTEST mode.");                
                    System.exit(0);   // terminate the JVM immediately
                }
            } else {
                System.out.println("Bitcoin core is not running.");
                System.out.println("Get Bitcoin core running before starting the application.");
                System.exit(0);   // terminate the JVM immediately
            }
            
            // ─────────────────────────────────────────────
            // 4. HD WALLET + ADDRESS MANAGER
            // ─────────────────────────────────────────────
            String existingMnemonic = null;

            masterKey = HdWalletService.createBip32HDWallet(network, existingMnemonic);  
            CachedWalletMnemMap.cachedNewObject("masterKey", masterKey);
            
            HDKeyDatabase dbSource = new HDKeyDatabase();
            HDKeyRepository hdRepo = new HDKeyRepository(dbSource);
            CachedWalletMnemMap.cachedNewObject("hdRepo",hdRepo);
            
            addrMgr = new HdAddressManager(masterKey, network, hdRepo, walletRpc);

            CachedWalletMnemMap.cachedNewObject("addrMgr", addrMgr);            
           
            String miningAddress;
            String consolidationAddress;
            if (Files.exists(LOG_FILE)) {
                System.out.println("Log file exists: " + LOG_FILE);
                String coupleAddresses = CLDAddress.readCLDAddress(LOG_FILE);
                miningAddress = coupleAddresses.split(":")[0];
                consolidationAddress = coupleAddresses.split(":")[1];
            } else {
                System.out.println("Log file does not exist: " + LOG_FILE);
                miningAddress  = addrMgr.getNextMiningAddress();
                HDKey hdKey = hdRepo.hdKeyByAddress(miningAddress).get();
                System.out.println("Mining address: " + miningAddress + ", path: " + hdKey.getPath());
                                
                consolidationAddress  = addrMgr.getNextChangeAddress();
                hdKey = hdRepo.hdKeyByAddress(consolidationAddress).get();
                System.out.println("Consolidation address: " + consolidationAddress + ", path: " + hdKey.getPath());
                
                String coupleAddresses = miningAddress + ":" + consolidationAddress;
                CLDAddress.saveCLDAddress(LOG_DIR, LOG_FILE, coupleAddresses);
            }
            
            CachedWalletMnemMap.cachedNewObject("miningAddress", miningAddress);
            CachedWalletMnemMap.cachedNewObject("consolidationAddress", consolidationAddress);
            
            String networkAddress = addrMgr.getNextMiningAddress();
            
            HDKey hdKey = hdRepo.hdKeyByAddress(networkAddress).get();
            byte[] nwkPubKeyBytes = hdKey.getPubKeyBytes();
            
            addrCache = loadAddressCache();  
            for (String addr : addrCache) {                
                System.out.println("[main] addr: " + addr);
            } 
            
            utxoSync = new UtxoSyncManager(walletRpc, addrMgr, indexStoreHandle, sqLteDb, addrCache, monitor);            
           
            chainIndexer = new ChainIndexer(walletRpc, sqLteDb, indexStoreHandle, monitor);
            // ─────────────────────────────────────────────
            // 6. NETWORK (P2P + LIGHTNING)
            // ─────────────────────────────────────────────
            
            NetworkManagerImpl networkManager = new NetworkManagerImpl(nwkPubKeyBytes);
            AppLogger.info("[Controller => main] After creating networkManager");
                    
            P2PServer server;
            P2PClient client;
            try {
                boolean isMerchant = node_usage.equals("merchant");
                String nodeUsage = "--" + node_usage;
                switch (nodeUsage) {
                    case "--customer" -> {
                            int customerPort = 8334;
                            server = new P2PServer(customerPort, networkManager);
                            server.start();
                            AppLogger.info("[Customer] P2P server started on port {}", customerPort);
                            client = new P2PClient(networkManager);
                            String merchantHost = "127.0.0.1";
                            int merchantPort = 8333;
                            if (!isMerchant) {
                                AppLogger.info("[Customer] Connecting to merchant {}:{}",
                                        merchantHost, merchantPort);
                                client.connectTo(merchantHost, merchantPort, true);
                                AppLogger.info("[Customer] Connected to merchant node");
                            }                          
                    }
                    case "--merchant", "--not-applicable" -> {
                            int merchantPort = 8333;
                            server = new P2PServer(merchantPort, networkManager);
                            server.start();
                            AppLogger.info("[Merchant] P2P server started on port {}", merchantPort);
                            client = new P2PClient(networkManager);
                            String customerHost = "127.0.0.1";
                            int customerPort = 8334;
                            if (!isMerchant) {
                                AppLogger.info("[Merchant] Optionally connecting to customer {}:{}",
                                        customerHost, customerPort);
                                client.connectTo(customerHost, customerPort, true);
                                AppLogger.info("[Merchant] Connected to customer node");
                            }   
                    } 
                    
                    default -> throw new IllegalArgumentException("Unknown node usage: " + nodeUsage);
                }
            } catch (IOException e) {
                AppLogger.error("[P2P] Failed to configure P2P for role {}: {}", node_usage, e.getMessage(), e);
            }
        
            AppLogger.info("[Controller => main] After call to client.connectTo");
            lightningService = new LightningServiceImpl(walletRpc);

            merchantHandler = new MerchantPaymentHandler(networkManager, lightningService);

            customerHandler = new CustomerPaymentHandler(networkManager, lightningService);

            // ─────────────────────────────────────────────
            // 7. APP START
            // ─────────────────────────────────────────────
            FundingGuard fundingGuard = new FundingGuard(
                    walletRpc, nodeRpc, addrMgr, network);
            
            feeEstimator = new FeeEstimator(walletRpc, BigDecimal.valueOf(1));
             
            BigDecimal estimateFee = feeEstimator.estimateFee(6);
            CoinSelector coinSelector = new CoinSelector(indexStoreHandle, walletRpc, estimateFee);
            
            ownSigner =  new OwnSigner(walletRpc, nodeRpc, 
                    fundingGuard, coinSelector, addrMgr, masterKey, network);   
            
            apisServer = new ApisServer(9080, this);

        } catch (Exception e) {
            AppLogger.error("Application error", e);
            System.exit(1);
        }
    }    
    
    public static Set<String> loadAddressCache() throws Exception {
        addressCache.clear();
        addressCache.addAll(addrMgr.getAllAddresses());
        return addressCache;
    }
        
    public boolean isBlockchainEmpty() throws Exception {
        System.out.println("[Controller => isBlockchainEmpty()] Checking block chain info!");
        BlockchainInfo blockchainInfo = walletRpc.getBlockchainInfo();
        AppLogger.info(blockchainInfo.toString());
        int blocks = blockchainInfo.blocks();
        return blocks == 0;
    }
  
    /**
     * Initialize wallet: import descriptors, start UTXO sync.
     * @throws java.lang.Exception
     */      
    public void initializeWallet() throws Exception { 

        AppLogger.info("Initializing wallet with SegWit descriptors...");

        // Network info
        BitcoinRpcClient.NetworkInfo netInfo = nodeRpc.getNetworkInfo();
        AppLogger.info("Connected to Bitcoin network: version={}, connections={}",
                netInfo.getVersion(), netInfo.getConnections());

        // Import descriptors into watch-only wallet
        importAccountDescriptors();    
                
        BlockchainInfo info = walletRpc.getBlockchainInfo();

        AppLogger.info("[Controller => initializeWallet()] info.blocks() = {}",
                    info.blocks());
        if (info.blocks() == 0) {
            
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            
            AppLogger.info("Bootstrapping regtest...");            
            bootstrapChain(202, miningAddress);    
        }

        // Immediate sync (uses unified performSync → handles fallback automatically)
        UtxoSyncManager.SyncResult initial = utxoSync.runOnce();
        
        if (initial != null) {
            AppLogger.info("Initial sync complete: height={}, utxos={}, total_sat={}",
                    initial.getBlockHeight(),
                    initial.getTotalUtxos(),
                    initial.getTotalValue());
        } else {
            AppLogger.warn("Initial sync skipped (already running)");
        }

        AppLogger.info("UTXO sync manager started (background loop active)");
        
        AppLogger.info("Wallet + Lightning initialized");
    }    
    
    public void importAccountDescriptors() throws Exception {
        
        AppLogger.info("Importing account descriptors into wallet '{}'...", WATCHONLY_WALLET_NAME);

        // ─────────────────────────────────────────────
        // 1. Detect network
        // ─────────────────────────────────────────────
        boolean isMainnet = false;
        boolean isTestnet = false;
        boolean isRegtest = false;

        switch (network) {
            case MAINNET -> isMainnet = true;
            case TESTNET -> isTestnet = true;
            case REGTEST -> isRegtest = true;
        }

        boolean useTestnetEncoding = isTestnet || isRegtest;
        int coinType = useTestnetEncoding ? 1 : 0;

        // ─────────────────────────────────────────────
        // 2. Derive BIP84 account (SegWit)
        // ─────────────────────────────────────────────
        Bip32HDWallet account84 =
            masterKey.derivePath(String.format("m/84'/%d'/0'", coinType));

        // IMPORTANT: encoding must match network
        String accountXpub = account84.toBase58(useTestnetEncoding, true);

        // fingerprint
        String fingerprint =
            String.format("%08x", masterKey.getFingerprintValue());

        // Debug (remove later)
        System.out.println("[Descriptor info] accountXpub = " + accountXpub);

        // ─────────────────────────────────────────────
        // 3. Build raw descriptors (external + change)
        // ─────────────────────────────────────────────
        String recvRaw = String.format(
            "wpkh([%s/84h/%dh/0h]%s/0/*)",
            fingerprint, coinType, accountXpub
        );

        String changeRaw = String.format(
            "wpkh([%s/84h/%dh/0h]%s/1/*)",
            fingerprint, coinType, accountXpub
        );

        // ─────────────────────────────────────────────
        // 4. Add checksum via Bitcoin Core
        // ─────────────────────────────────────────────
        String receiveDesc = getDescriptorWithChecksum(recvRaw);
        String changeDesc = getDescriptorWithChecksum(changeRaw);   
        
        //System.out.println("Receive descriptor: " + receiveDesc);
        //System.out.println("Change descriptor: " + changeDesc);
        
        ListDescriptorsResult existing =
                walletRpc.listDescriptors();

        for (ListDescriptorsResult.DescriptorInfo d
                : existing.getDescriptors()) {

            System.out.println(
                    "Descriptor: " + d.getDesc()
            );

            if (d.getRange() != null) {

                System.out.println(
                        "Range: " +
                        d.getRange()[0] +
                        " -> " +
                        d.getRange()[1]
                );
            }
        }         
        
        List<BitcoinRpcClient.DescriptorRequest> requests;
        if (descriptorAlreadyPresent(existing, receiveDesc)) {

            AppLogger.info(
                "Descriptor already imported, skipping."
            );

        } else {

            requests = List.of(
                new BitcoinRpcClient.DescriptorRequest(receiveDesc, true, false, 0),  // external
                new BitcoinRpcClient.DescriptorRequest(changeDesc, true, true, 0)  // change
            );

            List<BitcoinRpcClient.ImportDescriptorResult> results =
                walletRpc.importDescriptors(requests);

            // ─────────────────────────────────────────────
            // 6. Handle results
            // ─────────────────────────────────────────────
            for (int i = 0; i < results.size(); i++) {
                BitcoinRpcClient.ImportDescriptorResult r = results.get(i);
                BitcoinRpcClient.DescriptorRequest req = requests.get(i);

                if (!r.isSuccess()) {
                    AppLogger.info(
                        "Failed to import descriptor {}: {}",
                        req.getDesc(),
                        r.getError()
                    );
                } else if (r.getWarnings() != null) {
                    AppLogger.warn(
                        "Descriptor imported with warnings: {}",
                        r.getWarnings()
                    );
                } else {
                    AppLogger.info("Descriptor imported successfully.");
                }
            }
        }       
    }     
    
    public static void importXpubToRegtestWallet() throws Exception {
        AppLogger.info("[regtest] Importing xpub descriptor to regtest default wallet...");

        // ── 1. Derive xpub — MUST match watch-only wallet derivation ─────────
        boolean isMainnet = false;
        boolean isTestnet = false;
        boolean isRegtest = false;

        switch (network) {
            case MAINNET -> isMainnet = true;
            case TESTNET -> isTestnet = true;
            case REGTEST -> isRegtest = true;
        }

        boolean useTestnetEncoding = isTestnet || isRegtest;
        
        int localCoinType               = useTestnetEncoding ? 1 : 0;

        AppLogger.info("[regtest] network={} coinType={} useTestnetEncoding={}",
                network, localCoinType , useTestnetEncoding);

        Bip32HDWallet account84 = masterKey.derivePath(
                String.format("m/84'/%d'/0'", localCoinType));
        String accountXpub      = account84.toBase58(useTestnetEncoding, true);
        String fingerprint      = String.format("%08x",
                masterKey.getFingerprintValue());

        AppLogger.info("[regtest] accountXpub={}... fingerprint={} coinType={}",
                accountXpub.substring(0, 20), fingerprint, localCoinType);

        // ── 2. Build descriptors — must match watch-only wallet exactly ───────
        String recvRaw   = String.format(
                "wpkh([%s/84h/%dh/0h]%s/0/*)",   // ← coinType=1 for regtest
                fingerprint, localCoinType, accountXpub);

        String changeRaw = String.format(
                "wpkh([%s/84h/%dh/0h]%s/1/*)",
                fingerprint, localCoinType, accountXpub);

        // ── 3. Verify they match watch-only wallet descriptors ────────────────
        JSONObject watchOnly  = (JSONObject) BitcoinRpcClient.executeRpc("listdescriptors");
        JSONArray  watchDescs = watchOnly.getJSONArray("descriptors");

        AppLogger.info("[regtest] Watch-only descriptors:");
        for (int i = 0; i < watchDescs.length(); i++) {
            String d = watchDescs.getJSONObject(i).getString("desc");
            AppLogger.info("[regtest]   {}", d);
        }
        AppLogger.info("[regtest] Building receive: {}", recvRaw);
        AppLogger.info("[regtest] Building change:  {}", changeRaw);

        // ── 4. Get checksums ──────────────────────────────────────────────────
        String recvDesc   = getDescriptorWithChecksum(recvRaw);
        String changeDesc = getDescriptorWithChecksum(changeRaw);

        // ── 5. Check if already correctly imported ────────────────────────────
        JSONObject existing   = (JSONObject) BitcoinRpcClient.executeRpc("listdescriptors");
        JSONArray  existDescs = existing.getJSONArray("descriptors");

        boolean recvExists   = false;
        boolean changeExists = false;
        for (int i = 0; i < existDescs.length(); i++) {
            String d = existDescs.getJSONObject(i)
                                 .getString("desc").split("#")[0];
            if (d.equals(recvRaw))   recvExists   = true;
            if (d.equals(changeRaw)) changeExists = true;
        }

        if (recvExists && changeExists) {
            AppLogger.info("[regtest] Descriptors already present — skipping import.");
            return;
        }

        // ── 6. Import ─────────────────────────────────────────────────────────
        JSONArray importReq = new JSONArray();
        if (!recvExists) {
            importReq.put(new JSONObject()
                    .put("desc",      recvDesc)
                    .put("active",    true)
                    .put("internal",  false)
                    .put("timestamp", 0)
                    .put("range",     new JSONArray().put(0).put(999)));
        }
        if (!changeExists) {
            importReq.put(new JSONObject()
                    .put("desc",      changeDesc)
                    .put("active",    true)
                    .put("internal",  true)
                    .put("timestamp", 0)
                    .put("range",     new JSONArray().put(0).put(999)));
        }

        JSONArray results = (JSONArray) BitcoinRpcClient.executeRpc(
                "importdescriptors", importReq);

        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            if (!r.getBoolean("success")) {
                throw new BitcoinRpcException(
                        "[regtest] importdescriptors failed: "
                        + r.optJSONArray("error"));
            }
            AppLogger.info("[regtest] Descriptor {} imported ✅",
                    i == 0 ? "receive" : "change");
        }

        // ── 7. DO NOT rescan here — mining hasn't happened yet ────────────────
        //    Rescan is called AFTER bootstrapRegtest() in the startup sequence
        AppLogger.info("[regtest] Import complete — rescan will run after mining.");
    }    
    
    private static boolean descriptorAlreadyPresent(
        ListDescriptorsResult existing,
        String descriptor
    ) {

        for (ListDescriptorsResult.DescriptorInfo d
                : existing.getDescriptors()) {

            String existingDesc =
                    d.getDesc();

            // remove checksum suffix
            int idx =
                    existingDesc.indexOf('#');

            if (idx != -1) {
                existingDesc =
                        existingDesc.substring(0, idx);
            }

            idx = descriptor.indexOf('#');

            String normalized =
                    idx != -1
                            ? descriptor.substring(0, idx)
                            : descriptor;

            if (existingDesc.equals(normalized)) {
                return true;
            }
        }

        return false;
    }

    private static String getDescriptorWithChecksum(String raw) throws BitcoinRpcException {
        Object res = BitcoinRpcClient.executeRpc("getdescriptorinfo", raw);
        if (!(res instanceof JSONObject obj)) {
            throw new BitcoinRpcException("getdescriptorinfo: unexpected result " + res.getClass());
        }
        return obj.getString("descriptor");
    }

    public BigDecimal getFeeRateSatPerVb(int targetBlocks) throws BitcoinRpcException {
        return feeEstimator.estimateFeeRate(targetBlocks);
    }
    
    /**
     * Mine blocks on regtest to a fresh Core wallet address.
     * Useful for funding and confirming transactions quickly.
     * @param nBlocks
     * @return 
     * @throws java.lang.Exception
     */
    public List<String> mineBlocks(int nBlocks) throws Exception {
        if (!isRegtest()) {
            throw new IllegalStateException("Mining via generatetoaddress is only allowed on regtest.");
        }
        // This can be:
        // - an address from the Core wallet via getnewaddress, OR
        // - one of your own wallet addresses that Core knows via descriptors.
        //String miningAddress = nodeRpc.getNewAddress();  // or a dedicated miner address
        String miningAddress = addrMgr.getNextMiningAddress();
        AppLogger.info("[Controller => mineBlocks] miningAddress: {}", miningAddress);
        CachedWalletMnemMap.cachedNewObject("miningAddressStatus", "used");
        
        return nodeRpc.generateToAddress(nBlocks, miningAddress);
    }
    
    public int getBlockCount() throws IOException, BitcoinRpcException, Exception {
        return nodeRpc.getBlockCount();
    }
       
    public boolean isTestnet() {
        boolean isTestnet = false;
        if (network == AppNWKConfig.getInstance().getNetwork()) {   //SegWitAddress.Network.TESTNET) {
            isTestnet = true;
        }
        return isTestnet; 
    }
    
    public boolean isRegtest() { 
        boolean isRegtest = false;

        if (network == AppNWKConfig.getInstance().getNetwork()) {   //SegWitAddress.Network.REGTEST) {
            isRegtest = true;
        }
        return isRegtest; 
    }  
    
    /**
    * Returns true if this Bitcoin Core node is running in regtest mode.
    */
   private boolean isRegtestConfig() throws BitcoinRpcException {
       Object result =BitcoinRpcClient.executeRpc("getblockchaininfo");

       if (!(result instanceof org.json.JSONObject obj)) {
           throw new BitcoinRpcException(
               "getblockchaininfo returned unexpected type: " + result.getClass()
           );
       }

       String chain = obj.optString("chain", "");
       return "regtest".equalsIgnoreCase(chain);
   }

    private void printMCMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Show wallet info");
        System.out.println("2)  Show fee rate estimate");
        System.out.println("3)  Send coins");
        System.out.println("4)  Mine blocks (regtest)");
        System.out.println("5)  Merchant: receive payment");
        System.out.println("6)  Merchant: receive payment (API/custom capacity)");
        System.out.println("7)  Sync UTXOs now");           
        System.out.println("8)  Sync chain now"); 
        System.out.println("9)  Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }
    
    private void printCMMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Show wallet info");
        System.out.println("2)  Show fee rate estimate");
        System.out.println("3)  Send coins");
        System.out.println("4)  Mine blocks (regtest)");
        System.out.println("5)  Customer make payment");
        System.out.println("6)  Sync UTXOs now");         
        System.out.println("7)  Sync chain now"); 
        System.out.println("8)  Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }
    
    private void printODMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Show wallet info");
        System.out.println("2)  Show fee rate estimate");
        System.out.println("3)  Send coins");
        System.out.println("4)  Mine blocks (regtest)");
        System.out.println("5)  Sync UTXOs now");         
        System.out.println("6)  Sync chain now"); 
        System.out.println("7)  Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 9 — Merchant receives payment (fixed capacity)
    // ─────────────────────────────────────────────────────────────────

    private void merchantReceivePayment() {
        try {
            int chainHeight = getBlockCount();
            AppLogger.info("[App] Starting merchant payment flow at height={}", chainHeight);

            merchantHandler.receivePaymentForGoods(chainHeight);

            System.out.println("Payment received successfully.");
        } catch (PaymentException e) {
            System.out.println("Payment failed at stage [" + e.getStage() + "]: "
                + e.getMessage());
            AppLogger.error("[App] Merchant payment failed", e);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] Merchant payment error", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 10 — Merchant receives payment (custom capacity)
    // ─────────────────────────────────────────────────────────────────

    private void merchantGetPayment(Scanner scanner) throws BitcoinRpcException, Exception {
        try {
            System.out.print("Channel capacity in sat [1000000]: ");
            String input      = scanner.nextLine().trim();
            double capacitySat = input.isEmpty()
                ? LightningPaymentConstants.CHANNEL_CAPACITY_SAT
                : Double.parseDouble(input);

            int chainHeight = getBlockCount();
            AppLogger.info("[App] Starting API payment flow: capacity={} height={}",
                capacitySat, chainHeight);

            merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);

            System.out.println("API payment received successfully.");
        } catch (PaymentException e) {
            System.out.println("Payment failed at stage [" + e.getStage() + "]: "
                + e.getMessage());
            AppLogger.error("[App] API payment failed", e);
        } catch (NumberFormatException e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] API payment error", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 11 — Customer pays for goods
    // ─────────────────────────────────────────────────────────────────

    private void makeCustomerPayment(Scanner scanner, byte[] pubKeyBytes) {
        try {
            System.out.print("Channel ID: ");
            String channelId = scanner.nextLine().trim();
            if (channelId.isEmpty()) {
                System.out.println("Channel ID is required.");
                return;
            }

            int chainHeight = getBlockCount();
            AppLogger.info("[App] Starting customer payment: channelId={} height={}",
                channelId, chainHeight);

            customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);

            System.out.println("Payment sent successfully.");           

        } catch (PaymentException e) {
            System.out.println("Payment failed: " + e.getMessage());
            AppLogger.error("[App] Customer payment failed", e);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] Customer payment error", e);
        }
    }

    // ---------- MENU ACTIONS ----------

    // HD key mgmt + metadata
    
    public BigDecimal getWatchOnlyBalance() throws Exception {
        Object res = BitcoinRpcClient.executeRpc(
            "getbalance",
            "*",   // account (legacy / all)
            0,     // minconf
            true   // include_watchonly
        );

        switch (res) {
            case BigDecimal bd -> {
                return bd;
            }
            case Number num -> {
                // Fallback if someday executeRpc returns a plain number type
                return BigDecimal.valueOf(num.doubleValue());
            }
            default -> throw new IllegalStateException(
                    "Unexpected getbalance result type: " +
                            (res == null ? "null" : res.getClass())
            );
        }
    }
    
    private void showWalletInfo() throws Exception {
        // 1) Basic wallet info (name, watch-only balance, tx count, etc.)
        JSONObject walletInfo = (JSONObject) BitcoinRpcClient.executeRpc("getwalletinfo");
        String walletName     = walletInfo.optString("walletname", "<unnamed>");
        //boolean watchOnly     = walletInfo.optBoolean("watchonly", false);
        double balance        = walletInfo.optDouble("balance", 0.0);          // spendable (for normal wallets)
        BigDecimal watchOnlyBal   = getWatchOnlyBalance();   //walletInfo.optDouble("watchonly_balance", 0.0); // for watch-only wallets
        int txCount           = walletInfo.optInt("txcount", 0);
        //int keypoolSize       = walletInfo.optInt("keypoolsize", 0);

        System.out.println("=== Wallet Info ===");
        System.out.println("Wallet name        : " + walletName);
        System.out.println("Balance (spendable): " + balance + " BTC");
        System.out.println("Watch-only balance : " + watchOnlyBal + " BTC");
        System.out.println("Transaction count  : " + txCount);
        //System.out.println("Keypool size       : " + keypoolSize);

        // 2) Detailed UTXO overview (watch-only included)
        Object unspentRaw = BitcoinRpcClient.executeRpc(
                "listunspent",
                0,            // minconf
                9999999,      // maxconf
                new JSONArray(), // addresses (empty -> all)
                true          // include_unsafe (and watch-only by default for watch-only wallets) [web:140]
        );
        JSONArray unspent = (JSONArray) unspentRaw;
        int utxoCount = unspent.length();

        double totalUtxoAmount = 0.0;
        Set<String> addresses  = new HashSet<>();

        for (int i = 0; i < unspent.length(); i++) {
            JSONObject u = unspent.getJSONObject(i);
            totalUtxoAmount += u.getDouble("amount");
            addresses.add(u.optString("address", "<unknown>"));
        }
        
        System.out.println();
        System.out.println("=== UTXOs ===");
        System.out.println("UTXO count        : " + utxoCount);
        System.out.println("UTXO total amount : " + totalUtxoAmount + " BTC");

        // 3) Show a small sample of addresses being watched
        List<String> addrList = new ArrayList<>(addresses);
        System.out.println();
        System.out.println("=== Watched addresses (sample) ===");
        int maxToShow = Math.min(addrList.size(), 10);
        for (int i = 0; i < maxToShow; i++) {
            System.out.println("  " + addrList.get(i));
        }
        if (addrList.size() > maxToShow) {
            System.out.println("  ... (" + (addrList.size() - maxToShow) + " more)");
        }
    }
    
    // Fee estimate using Core's estimatesmartfee / getmempoolinfo, etc.
    private void showFeeEstimate() throws Exception {
        System.out.println("=== Fee Estimates (BTC/kB and sat/vB) ===");

        // Target confirmation windows (in blocks)
        int[] targets = {1, 2, 3, 6, 12};

        for (int target : targets) {
            BigDecimal feeRate = getFeeRateSatPerVb(target);
            
            System.out.printf(
                    "Target %2d blocks: %.8f BTC/kvB%n",
                    target,
                    feeRate
            );
        }

        System.out.println();
    }    
  
    // Full PSBT send: walletcreatefundedpsbt -> Java signing -> finalizepsbt -> sendrawtransaction[web:261]
    private void sendCoins() throws Exception {  
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Send Coins ---");
        String destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("Invalid address for network " + network);
        }
                
        System.out.println("Enter this addtress: "  + destAddress + " as destination");
        System.out.print("Destination address: ");
        String toAddress = scanner.nextLine().trim();
        if (toAddress.isEmpty()) {
            System.out.println("Destination address is required.");
            return;
        }

        System.out.print("Amount BTC (e.g. 0.1): ");
        String amountStr = scanner.nextLine().trim();
        if (amountStr.isEmpty()) {
            System.out.println("Amount is required.");
            return;
        }
        BigDecimal amountBtc = new BigDecimal(amountStr);

        System.out.print("Target blocks [6]: ");
        String targetStr = scanner.nextLine().trim();
        int targetBlocks = targetStr.isEmpty() ? 6 : Integer.parseInt(targetStr);

        System.out.println("Creating, signing, and broadcasting transaction...");
        
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("Invalid address for network " + network);
        }
                
        String txid = ownSigner.sendCoins(toAddress, amountBtc, changeAddress);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);
        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
       
        addrMgr.ensureLookahead();
                
        System.out.println("Broadcasted txid: " + txid);
        System.out.println("Verify in Core with:");
        System.out.println("  bitcoin-cli -regtest getrawtransaction " + txid + " 1");
        
        destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("Invalid address for network " + network);
        }
        changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("Invalid address for network " + network);
        }
                               
        String tranxid = ownSigner.sendCoins(destAddress, amountBtc, changeAddress, 
                masterKey, network, targetBlocks); 
        addrMgr.markUsed(destAddress);
        addrMgr.markUsed(changeAddress);
        
        System.out.println("Broadcasted txid: " + tranxid);
        System.out.println("Verify in Core with:");
        System.out.println("  bitcoin-cli -regtest getrawtransaction " + tranxid + " 1");  
        
        // ── Confirm on regtest ────────────────────────────────────────────────
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info("[sendCoins] Mined 1 block to confirm tx.");
        }
        
        walletAnalyzer.analyzeWallet();
        
        addrMgr.ensureLookahead();
        
        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            System.out.println("[sendCoins()] usedReceiveAddress: " + usedReceiveAddress);
        }
        
        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            System.out.println("[sendCoins()] usedChangeAddress: " + usedChangeAddress);
        }        
    }

    public int mineBlocks() throws Exception {
        if (!isRegtest()) {
            System.out.println("Mining is only supported on regtest in this console.");
            return 0;
        }
        
        Scanner scanner = new Scanner(System.in);
        System.out.print("How many blocks to mine? [1]: ");
        String in = scanner.nextLine().trim();
        int nBlocks = in.isEmpty() ? 1 : Integer.parseInt(in);

        System.out.println("Mining " + nBlocks + " block(s) on regtest...");
        var hashes = mineBlocks(nBlocks);  // calls BitcoinWalletApp.mineBlocks

        System.out.println("Mined blocks:");
        for (String h : hashes) {
            System.out.println("  " + h);
        }
        
        int height = getBlockCount();
        System.out.println("Tip height is now (via Core): " + height);
                
        System.out.println("Current block height: " + height); 
        return hashes.size();
    }
       
    private static void bootstrapChain(int numBlocks, String address) throws Exception {
        List<String> hashes = nodeRpc.generateToAddress(numBlocks, address);  
        
        if (!hashes.isEmpty()) {
            System.out.printf(
                "[App] Mined %d block(s). First=%s Last=%s%n",
                hashes.size(),
                hashes.get(0).substring(0, 12),
                hashes.get(hashes.size() - 1).substring(0, 12)
            );
        } else {
            System.out.printf("[App] Mined 0 block(s).%n");
        }
        
        BlockchainInfo blockchainInfo = walletRpc.getBlockchainInfo();
        AppLogger.info(blockchainInfo.toString());
    }
   
    private static String getNodeUsage() {
        String input;
        
        System.out.println("\nNode usage mode setting."); 
        System.out.println("Application includes merchant and customer functionalities.");
        System.out.println("Select usage mode (merchant and customer) to enable on CLI menu.");
        Scanner scanner = new Scanner(System.in); 
        
        while (true) {
            System.out.print("Enter 1 -> 'merchant', 2 -> 'customer', 3 -> 'not-applicable'  or 4 -> 'quit' to exit: ");

            int num = scanner.nextInt();

            switch (num) {
                case 1:
                {
                    input = "merchant";
                    System.out.println("Input: " + input);
                    System.out.println(); 
                    return input;
                }
                case 2:
                {
                    input = "customer";
                    System.out.println("Input: " + input);
                    System.out.println();
                    return input;
                }
                case 3:
                {
                    input = "not-applicable";
                    System.out.println("Input: " + input);
                    System.out.println();
                    return input;
                }
                case 4:
                    System.out.println("Input: quit");
                    System.out.println("Goodbye.");
                    System.exit(0);   // terminate the JVM immediately
                default:
                    System.out.println("Invalid choice, please try again.");
                    break;
            }
        }
    }
    
    @Override
    public void run() {
        byte[] publicKeyBytes = pubKeyBytes;
        Scanner scanner = new Scanner(System.in);
        while (running) {
            try {
                // 1. Synchronized block to check shared variables safely
                synchronized (monitor) {
                    // Wait until both workers are free
                    while (monitor.isAnyWorkerBusy()) {
                        monitor.wait(); // Release lock and wait for workers to notify they are done
                    }
                }

                if (node_usage.equals("merchant")) {
                    printMCMenu();
                    String input = scanner.nextLine().trim();

                    int inputNum = Integer.parseInt(input);
                    switch (inputNum) {
                        case 1:
                            showWalletInfo();
                            break;
                        case 2:
                            showFeeEstimate();
                            break;
                        case 3:
                            sendCoins();
                            break;
                        case 4:
                            mineBlocks();
                            break;
                        case 5:
                            merchantReceivePayment();
                            break;
                        case 6:
                            merchantGetPayment(scanner);
                            break;
                        case 7:
                        case 8:
                            int chosenWorker = (inputNum == 7) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 9:
                            System.out.println("--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    System.out.println("✅ Consolidation complete!");
                                    System.out.println("txid: " + txid);
                                    System.out.println("Your change UTXOs have been merged"
                                            + " into one spendable UTXO.");
                                    System.out.println("Verify with:");
                                    System.out.println("  bitcoin-cli -regtest"
                                            + " getrawtransaction " + txid + " 1");

                                } else {
                                    System.out.println("No change UTXOs to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error("[consolidate] Failed: {}", e.getMessage(), e);
                                System.out.println("Consolidation failed: " + e.getMessage());
                            }
                            break;
                        case 0:
                            System.out.println("Goodbye.");
                            System.exit(0);
                        default:
                            break;                    
                    }
                } else if (node_usage.equals("customer")) {
                    printCMMenu();
                    String input = scanner.nextLine().trim();

                    int inputNum = Integer.parseInt(input);
                    switch (inputNum) {
                        case 1:
                            showWalletInfo();
                            break;
                        case 2:
                            showFeeEstimate();
                            break;
                        case 3:
                            sendCoins();
                            break;
                        case 4:
                            mineBlocks();
                            break;                    
                        case 5:
                            makeCustomerPayment(scanner, publicKeyBytes);
                            break;
                        case 6:
                        case 7:
                            int chosenWorker = (inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 8:
                            System.out.println("--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    System.out.println("✅ Consolidation complete!");
                                    System.out.println("txid: " + txid);
                                    System.out.println("Your change UTXOs have been merged"
                                            + " into one spendable UTXO.");
                                    System.out.println("Verify with:");
                                    System.out.println("  bitcoin-cli -regtest"
                                            + " getrawtransaction " + txid + " 1");

                                } else {
                                    System.out.println("No change UTXOs to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error("[consolidate] Failed: {}", e.getMessage(), e);
                                System.out.println("Consolidation failed: " + e.getMessage());
                            }
                            break;
                        case 0:
                            System.out.println("Goodbye.");
                            System.exit(0);
                        default:
                            break;                    
                    }   
                } else if (node_usage.equals("not-applicable")) {
                    printODMenu();
                    String input = scanner.nextLine().trim();

                    int inputNum = Integer.parseInt(input);
                    switch (inputNum) {
                        case 1:
                            showWalletInfo();
                            break;
                        case 2:
                            showFeeEstimate();
                            break;
                        case 3:
                            sendCoins();
                            break;
                        case 4:
                            mineBlocks();
                            break;
                        case 5:
                        case 6:
                            int chosenWorker = (inputNum == 5) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 7:
                            System.out.println("--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    System.out.println("✅ Consolidation complete!");
                                    System.out.println("txid: " + txid);
                                    System.out.println("Your change UTXOs have been merged"
                                            + " into one spendable UTXO.");
                                    System.out.println("Verify with:");
                                    System.out.println("  bitcoin-cli -regtest"
                                            + " getrawtransaction " + txid + " 1");

                                } else {
                                    System.out.println("No change UTXOs to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error("[consolidate] Failed: {}", e.getMessage(), e);
                                System.out.println("Consolidation failed: " + e.getMessage());
                            }
                            break;
                        case 0:
                            System.out.println("Goodbye.");
                            System.exit(0);
                        default:
                            break;                    
                    }
                }
                        
                // Small delay to prevent tight looping
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception ex) {
                System.getLogger(BTCHDWallet.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }
    
    public void handleDelegation(int workerId) {
        try {
            // 1. Wait until both workers are free
            while (monitor.isAnyWorkerBusy()) {
                // Sleep briefly so we don't print "Waiting..." 10,000 times a second
                // while the user isn't typing anything.
                Thread.sleep(200); 
            }

            // 2. Both are free! Make the decision.
            AppLogger.info("Controller (NODEMHDWallet): Both Syncers are free. I will assign a task to a Syncer.");
            
            // 3. Assign the task (This safely sets the busy flag now)
            monitor.assignTaskTo(workerId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    public String getWalletInfoAsJson() throws Exception {
        // Adjust these calls to your actual RPC interface
        BigDecimal confirmedBtc   = BigDecimal.valueOf(walletRpc.getConfirmedBalanceBtc());
        BigDecimal unconfirmedBtc = BigDecimal.valueOf(walletRpc.getUnconfirmedBalanceBtc());
        int numUtxos              = walletRpc.getUtxoCount();

        String nextReceiveAddress = addrMgr.getNextMiningAddress();
        String nextChangeAddress  = addrMgr.getNextChangeAddress();

        JSONObject json = new JSONObject();
        json.put("confirmedBalanceBtc", confirmedBtc.toPlainString());
        json.put("unconfirmedBalanceBtc", unconfirmedBtc.toPlainString());
        json.put("totalBalanceBtc", confirmedBtc.add(unconfirmedBtc).toPlainString());
        json.put("numUtxos", numUtxos);
        json.put("nextReceiveAddress", nextReceiveAddress);
        json.put("nextChangeAddress", nextChangeAddress);

        return json.toString();
    }

    public String getFeeEstimateAsJson() throws IOException, BitcoinRpcException {
        int targetBlocks = 6;
        long feerateSatPerVb = walletRpc.estimateSmartFeeSatPerVb(targetBlocks); // or your own estimator

        JSONObject json = new JSONObject();
        json.put("feerateSatPerVb", feerateSatPerVb);
        json.put("targetBlocks", targetBlocks);

        return json.toString();
    }

    public int mineBlocksApi(int numBlocks, String miningAddress) throws Exception {
        if (numBlocks <= 0) {
            numBlocks = 1;
        }

        if (miningAddress == null || miningAddress.isBlank()) {
            // Use cached or derived mining address
            Object cached = CachedWalletMnemMap.getObject("miningAddress");
            if (cached != null) {
                miningAddress = (String) cached;
            } else {
                miningAddress = addrMgr.getNextMiningAddress();
            }
        }

        if (!walletRpc.isValidCoreAddress(miningAddress)) {
            throw new IllegalArgumentException("Invalid mining address for network " + network);
        }

        BitcoinRpcClient.executeRpc("generatetoaddress", numBlocks, miningAddress);
        AppLogger.info("[mineBlocksApi] Mined {} block(s) to {}", numBlocks, miningAddress);
        return numBlocks;
    }

    public String createMerchantInvoiceJson() throws IOException {
        // Example: take current invoice from lightningService or internal state.
        Invoice currentInvoice = lightningService.getCurrentInvoice(); // implement or adjust
        
        // Get current invoice (creates new one if expired)
        if (currentInvoice == null) {
            return "{\"error\":\"No active invoice\"}";
        }

        System.out.println("Invoice: " + currentInvoice);
        System.out.println("Amount: " + currentInvoice.getAmountSat() + " sat");
        System.out.println("Payment Hash: " + HexUtils.bytesToHex(currentInvoice.getPaymentHash()));

        JSONObject json = new JSONObject();
        byte[] hex = currentInvoice.getPaymentHash();
        json.put("paymentHash", HexUtils.bytesToHex(hex));   //getPaymentHashHex());
        json.put("amountSat", currentInvoice.getAmountSat());
        json.put("description", currentInvoice.getDescription());
        json.put("expirySeconds", currentInvoice.getExpiryEpoch());   

        return json.toString();
    }    
    
    public String consolidateChangeApi() throws Exception {
        String txid = ownSigner.convertChangeToSpendableUtxo();
        if (txid != null) {
            AppLogger.info("[HDWallet] Consolidated change, txid={}", txid);
        } else {
            AppLogger.info("[HDWallet] No change UTXOs to consolidate.");
        }
        return txid;
    }    
    
    // Pure logic: no Scanner, no System.in, suitable for HTTP APIs
    public SendCoinsResult sendCoinsApi(String toAddress, BigDecimal amountBtc, int targetBlocks) throws Exception {
        // Validate destination
        if (toAddress == null || toAddress.isBlank()) {
            throw new IllegalArgumentException("Destination address is required.");
        }
        if (!walletRpc.isValidCoreAddress(toAddress)) {
            throw new IllegalArgumentException("Invalid destination address for network " + network);
        }
        if (amountBtc == null || amountBtc.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        if (targetBlocks <= 0) {
            targetBlocks = 6;
        }

        AppLogger.info("[sendCoinsApi] to={} amount={} targetBlocks={}", toAddress, amountBtc, targetBlocks);

        // Choose change address
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("Invalid change address for network " + network);
        }

        // First transaction: basic sendCoins(to, amount, change)
        String txid1 = ownSigner.sendCoins(toAddress, amountBtc, changeAddress);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);

        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
        addrMgr.ensureLookahead();

        // Second transaction: sendCoins with masterKey/network/targetBlocks
        String destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("Invalid mining address for network " + network);
        }

        String changeAddress2 = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress2)) {
            throw new IllegalArgumentException("Invalid change address for network " + network);
        }

        String txid2 = ownSigner.sendCoins(
            destAddress,
            amountBtc,
            changeAddress2,
            masterKey,
            network,
            targetBlocks
        );
        addrMgr.markUsed(destAddress);
        addrMgr.markUsed(changeAddress2);

        // Optional: auto‑mine confirmation on regtest
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info("[sendCoinsApi] Mined 1 block to confirm txs.");
        }

        // Re-analyze wallet and log used addresses
        walletAnalyzer.analyzeWallet();
        addrMgr.ensureLookahead();

        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            AppLogger.info("[sendCoinsApi] usedReceiveAddress: {}", usedReceiveAddress);
        }

        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            AppLogger.info("[sendCoinsApi] usedChangeAddress: {}", usedChangeAddress);
        }

        return new SendCoinsResult(txid1, txid2);
    }
    
    public ReceivePaymentResult receivePaymentApisWrapper(double capacitySat, int chainHeight) throws PaymentException {
        // Internally this method runs the flow you pasted
        merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);
        // If you store the last opened channelId somewhere, you can return it
        String lastChannelId = lightningService.getLastOpenedChannelId();
        return new ReceivePaymentResult(lastChannelId);
    }
    
    // Pure API method – no Scanner, ready for ApisServer
    public void makeCustomerPaymentApi(String channelId, byte[] pubKeyBytes) throws Exception {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("Channel ID is required.");
        }

        int chainHeight = getBlockCount();
        AppLogger.info("[App] Starting customer payment (API): channelId={} height={}",
            channelId, chainHeight);

        customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);
        AppLogger.info("[App] Customer payment via API succeeded.");
    }
    
    public static void main(String[] args) throws Exception {                    
        // Create the threads
        BTCHDWallet nodeHDWallet = new BTCHDWallet();

        nodeHDWallet.initializeWallet();   // MUST RETURN            
            
        for (String addr : addrCache) {                
            if (!walletRpc.isValidCoreAddress(addr)) {
                throw new IllegalArgumentException("Invalid address for network " + network);
            }
        }            
       
        chainIndexer.initialSync();
            
        Thread utxoSyncThread = new Thread(utxoSync, "UtxoSyncManager");
        Thread chainIndexerThread = new Thread(chainIndexer, "chainIndexer");
            
        // Workers will immediately enter waitForSignal and wait
        utxoSyncThread.start();
        chainIndexerThread.start();
            
        // Controller starts and checks the variables
        nodeHDWallet.start();       
        
        // Start APIs server on e.g. port 8080
        //ApisServer apisServer = new ApisServer(8080, thisApp);
        nodeHDWallet.apisServer.start();        
    }
}