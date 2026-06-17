/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package com.bitcoin.hidzmq.hdwallet;

/**
 *
 * @author DAOMOSDA
 */

import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.MAINNET;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.REGTEST;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.TESTNET;

import com.bitcoin.hdwallet.apisserver.ApisServer;
import com.bitcoin.hdwallet.cacheUtil.CLDAddress;
import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import com.bitcoin.hdwallet.cacheUtil.WordFileStore;
import com.bitcoin.hdwallet.chainindexing.ChainIndexer;
import com.bitcoin.hdwallet.chainindexing.ChainSyncer;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.AppNWKConfig;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.core.BlockchainInfo;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.core.FeeEstimator;
import com.bitcoin.hdwallet.core.ListDescriptorsResult;
import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.crypto.SegWitAddress;
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
import com.bitcoin.hdwallet.model.Utxo;
import com.bitcoin.hdwallet.networkmanager.NetworkManagerImpl;
import com.bitcoin.hdwallet.networkmanager.P2PClient;
import com.bitcoin.hdwallet.networkmanager.P2PServer;
import com.bitcoin.hdwallet.realtimeblocks.RegtestZmqListener;
import com.bitcoin.hdwallet.realtimeblocks.RegtestZmqListener.SyncResult;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.repository.HDKeyRepository;
import com.bitcoin.hdwallet.tranxcontrol.CoinSelector;
import com.bitcoin.hdwallet.tranxcontrol.FundingGuard;
import com.bitcoin.hdwallet.tranxcontrol.OwnSigner;
import com.bitcoin.hdwallet.tranxcontrol.WalletAnalyzer;
import com.bitcoin.hdwallet.trezorhardware.HardwareDeviceInfo;
import com.bitcoin.hdwallet.trezorhardware.TrezorSigner;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.BindException;
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
 * @author DAOMOSDA
 */

public class HIDZMQHDWallet extends Thread {
    
    private static String className = "HIDZMQHDWallet";
    
    private FeeEstimator feeEstimator;
    private FundingGuard fundingGuard;
    private CoinSelector coinSelector;
    private ChainIndexRepository indexStoreHandle;
    private static ChainIndexer chainIndexer;
    private static ChainSyncer chainSyncer;
    
    private static Bip32HDWallet masterKey;
    
    private  byte[] pubKeyBytes;  
  
    private static final Set<String> addressCache = ConcurrentHashMap.newKeySet();
    
    private static HdAddressManager addrMgr;
   
    private static String WATCHONLY_WALLET_NAME;
    private static SegWitAddress.Network network;
    
    private static BitcoinRpcClient nodeRpc;      // base RPC (no wallet suffix)
    private static BitcoinRpcClient walletRpc;    // /wallet/btcnode_watchonly
    
    // ── new lightning fields ──────────────────────────────────────────
    private MerchantPaymentHandler merchantHandler;
    private CustomerPaymentHandler customerHandler;
    private LightningService lightningService;
    private ApisServer apisServer;
    
    private OwnSigner ownSigner;
    private TrezorSigner trezor;
    
    private static final SharedMonitor monitor = new SharedMonitor();
    private boolean running = true;
    
    private static Set<String> addrCache;   
    private String node_usage;
    
    private static RegtestZmqListener regtestZmqListener;

    public HIDZMQHDWallet() { 

        try {
            
            Security.addProvider(new BouncyCastleProvider());
            
            this.node_usage = getNodeUsage(); 
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

            this.indexStoreHandle = new ChainIndexRepository(sqLteDb);
            
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
                    AppLogger.info(className, "Bitcoin Core is running in REGTEST mode.");
                    nodeRpc.ensureWatchOnlyWalletExistsAndLoaded(WATCHONLY_WALLET_NAME);
                    walletRpc = nodeRpc.forWallet(WATCHONLY_WALLET_NAME);
                } else {
                    AppLogger.info(className, "Bitcoin Core is NOT in REGTEST mode.");
                    AppLogger.info(className, "Re-configure Bitcoin core to run in REGTEST mode.");                
                    System.exit(0);   // terminate the JVM immediately
                }
            } else {
                AppLogger.info(className, "Bitcoin core is not running.");
                AppLogger.info(className, "Get Bitcoin core running before starting the application.");
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
                AppLogger.info(className, "Log file exists: {}", LOG_FILE);
                String coupleAddresses = CLDAddress.readCLDAddress(LOG_FILE);
                miningAddress = coupleAddresses.split(":")[0];
                consolidationAddress = coupleAddresses.split(":")[1];
            } else {
                AppLogger.info(className, "Log file does not exist: {}", LOG_FILE);
                miningAddress  = addrMgr.getNextMiningAddress();
                HDKey hdKey = hdRepo.hdKeyByAddress(miningAddress).get();
                AppLogger.info(className, "Mining address: {}, path: {}", miningAddress, hdKey.getPath());
                                
                consolidationAddress  = addrMgr.getNextChangeAddress();
                hdKey = hdRepo.hdKeyByAddress(consolidationAddress).get();
                AppLogger.info(className, "Consolidation address: {}, path: {}", consolidationAddress, hdKey.getPath());
                
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
                AppLogger.info(className, " addr: {}", addr);
            } 
           
            chainIndexer = new ChainIndexer(walletRpc, sqLteDb, indexStoreHandle, monitor);
            
            chainSyncer = new ChainSyncer(walletRpc, sqLteDb, indexStoreHandle, monitor);
            
            AppLogger.info(className, "#####################  Point 0 #####################\n");
            // ── Create ZMQ components ─────────────────────────────────────────────
            AppLogger.info(className, " Verifying ZMQ configuration...");

            int startHeight = walletRpc.getCoreTipHeight();

            regtestZmqListener = new RegtestZmqListener(walletRpc, addrMgr, indexStoreHandle, 
                    addressCache, monitor, startHeight);

            AppLogger.info(className, "#####################  Point 1 #####################\n");
            
            NetworkManagerImpl networkManager = new NetworkManagerImpl(nwkPubKeyBytes);
            AppLogger.info(className, "[Controller => main] After creating networkManager");
                    
            P2PServer server;
            P2PClient client;
            try {
                boolean isMerchant = node_usage.equals("merchant");
                String nodeUsage = "--" + node_usage;
                switch (nodeUsage) {
                    case "--customer" -> {
                            int customerPort = 8379;
                            server = new P2PServer(customerPort, networkManager);
                            server.start();
                            AppLogger.info(className, "[Customer] P2P server started on port {}", customerPort);
                            client = new P2PClient(networkManager);
                            String merchantHost = "127.0.0.1";
                            int merchantPort = 8377;
                            if (!isMerchant) {
                                AppLogger.info(className, "[Customer] Connecting to merchant {}:{}",
                                        merchantHost, merchantPort);
                                client.connectTo(merchantHost, merchantPort, true);
                                AppLogger.info(className, "[Customer] Connected to merchant node");
                            }                          
                    }
                    case "--merchant", "--not-applicable" -> {
                            int merchantPort = 8377;
                            server = new P2PServer(merchantPort, networkManager);
                            server.start();
                            AppLogger.info(className, "[Merchant] P2P server started on port {}", merchantPort);
                            client = new P2PClient(networkManager);
                            String customerHost = "127.0.0.1";
                            int customerPort = 8379;
                            if (!isMerchant) {
                                AppLogger.info(className, "[Merchant] Optionally connecting to customer {}:{}",
                                        customerHost, customerPort);
                                client.connectTo(customerHost, customerPort, true);
                                AppLogger.info(className, "[Merchant] Connected to customer node");
                            }   
                    } 
                    
                    default -> throw new IllegalArgumentException("Unknown node usage: " + nodeUsage);
                }
            } catch (IOException e) {
                AppLogger.error(className, "[P2P] Failed to configure P2P for role {}: {}", node_usage, e.getMessage());
            }        
            
            lightningService = new LightningServiceImpl(walletRpc);

            merchantHandler = new MerchantPaymentHandler(networkManager, lightningService);

            customerHandler = new CustomerPaymentHandler(networkManager, lightningService);

            AppLogger.info(className, "#####################  Point 2 #####################\n");
            // ─────────────────────────────────────────────
            // 7. APP START
            // ─────────────────────────────────────────────
            fundingGuard = new FundingGuard(walletRpc, nodeRpc, addrMgr, network);
            
            feeEstimator = new FeeEstimator(walletRpc, BigDecimal.valueOf(1));
             
            BigDecimal estimateFee = feeEstimator.estimateFee(6);
            coinSelector = new CoinSelector(indexStoreHandle, walletRpc, estimateFee);
            
            trezor = new TrezorSigner(walletRpc);
            AppLogger.info(className, "--- Hardware Wallet Status ---");
            List<HardwareDeviceInfo> devices = trezor.listDevices();
            if (devices.isEmpty()) {
                AppLogger.info(className, "No hardware wallet detected.");
            } else {
                for (HardwareDeviceInfo d : devices) {
                    AppLogger.info(className, "vendor: {}, model: {}, serial: {}",
                         d.vendor(), d.model(), d.serialNumber());
                    //System.out.println("  " + d.vendor() + " " + d.model()
                    //        + " (serial=" + d.serialNumber() + ")");
                }
                trezor.connect();
                AppLogger.info(className, "Master fingerprint: {}",
                         trezor.getMasterFingerprint());
            } 
            
            CachedWalletMnemMap.cachedNewObject("devices", devices);
            ownSigner =  new OwnSigner(walletRpc, nodeRpc, trezor,
                    fundingGuard, coinSelector, addrMgr, masterKey, network);  
            
            int apiPort = 8080;

            //boolean started = false;
            while (apiPort < 8780 + 100) {
                try {
                    apisServer = new ApisServer(apiPort, this);
                    apisServer.start(); 
                    AppLogger.info(className, " Port selected and used: {}", apiPort);
                    break;
                } catch (BindException e) {
                    AppLogger.error(className, " Port {} in use, trying {}", port, (port + 1));
                    apiPort++;
                }
            }            
            
            AppLogger.info(className, "#####################  Point 3 #####################\n");

        } catch (Exception e) {
            AppLogger.error(className, "Application error", e);
            apisServer.stop();
            System.exit(1);
        }
    }     
    
    public static Set<String> loadAddressCache() throws Exception {
        addressCache.clear();
        addressCache.addAll(addrMgr.getAllAddresses());
        return addressCache;
    }
        
    public boolean isBlockchainEmpty() throws Exception {
        AppLogger.info(className, "[Controller => isBlockchainEmpty()] Checking block chain info!");
        BlockchainInfo blockchainInfo = walletRpc.getBlockchainInfo();
        AppLogger.info(className, blockchainInfo.toString());
        int blocks = blockchainInfo.blocks();
        return blocks == 0;
    }
  
    /**
     * Initialize wallet: import descriptors, start UTXO sync.
     * @throws java.lang.Exception
     */      
    public static void initializeWallet() throws Exception { 

        AppLogger.info(className, "Initializing wallet with SegWit descriptors...");

        // Network info
        BitcoinRpcClient.NetworkInfo netInfo = nodeRpc.getNetworkInfo();
        AppLogger.info(className, "Connected to Bitcoin network: version={}, connections={}",
                netInfo.getVersion(), netInfo.getConnections());

        // Import descriptors into watch-only wallet
        importAccountDescriptors();    
                
        BlockchainInfo info = walletRpc.getBlockchainInfo();

        AppLogger.info(className, "[Controller => initializeWallet()] info.blocks() = {}",
                    info.blocks());
        if (info.blocks() == 0) {
            
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            
            AppLogger.info(className, "Bootstrapping regtest...");    
            CachedWalletMnemMap.cachedNewObject("blocks", 200);            
            bootstrapChain(202, miningAddress); 
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        } else {
            SyncResult initial = regtestZmqListener.runOnce();     
        
            if (initial != null) {
                AppLogger.info(className, "Initial sync complete: height={}, utxos={}, total_sat={}",
                        initial.getBlockHeight(),
                        initial.getTotalUtxos(),
                        initial.getTotalValue());
            } else {
               AppLogger.warn(className, "Initial sync skipped (already running)");
            }
        }  
        
        chainIndexer.initialSync();
        
        chainSyncer.catchUp();
 
        AppLogger.info(className, "Wallet + Lightning initialized");
    }    
    
    public static void importAccountDescriptors() throws Exception {
        
        AppLogger.info(className, "Importing account descriptors into wallet '{}'...", WATCHONLY_WALLET_NAME);

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
        AppLogger.info(className, "[Descriptor info] accountXpub = " + accountXpub);

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
        
        //AppLogger.info(className, "Receive descriptor: " + receiveDesc);
        //AppLogger.info(className, "Change descriptor: " + changeDesc);
        
        ListDescriptorsResult existing =
                walletRpc.listDescriptors();

        for (ListDescriptorsResult.DescriptorInfo d
                : existing.getDescriptors()) {

            AppLogger.info(className, 
                    "Descriptor: " + d.getDesc()
            );

            if (d.getRange() != null) {

                AppLogger.info(className, 
                        "Range: " +
                        d.getRange()[0] +
                        " -> " +
                        d.getRange()[1]
                );
            }
        }         
        
        List<BitcoinRpcClient.DescriptorRequest> requests;
        if (descriptorAlreadyPresent(existing, receiveDesc)) {

            AppLogger.info(className, 
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
                    AppLogger.info(className, 
                        "Failed to import descriptor {}: {}",
                        req.getDesc(),
                        r.getError()
                    );
                } else if (r.getWarnings() != null) {
                    AppLogger.warn(className, 
                        "Descriptor imported with warnings: {}",
                        r.getWarnings()
                    );
                } else {
                    AppLogger.info(className, "Descriptor imported successfully.");
                }
            }
        }       
    }     
    
    public static void importXpubToRegtestWallet() throws Exception {
        AppLogger.info(className, "[regtest] Importing xpub descriptor to regtest default wallet...");

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
        
        int localCoinType = useTestnetEncoding ? 1 : 0;

        AppLogger.info(className, "[regtest] network={} coinType={} useTestnetEncoding={}",
                network, localCoinType , useTestnetEncoding);

        Bip32HDWallet account84 = masterKey.derivePath(
                String.format("m/84'/%d'/0'", localCoinType));
        String accountXpub = account84.toBase58(useTestnetEncoding, true);
        String fingerprint = String.format("%08x",
                masterKey.getFingerprintValue());

        AppLogger.info(className, "[regtest] accountXpub={}... fingerprint={} coinType={}",
                accountXpub.substring(0, 20), fingerprint, localCoinType);

        // ── 2. Build descriptors — must match watch-only wallet exactly ───────
        String recvRaw = String.format(
                "wpkh([%s/84h/%dh/0h]%s/0/*)",   // ← coinType=1 for regtest
                fingerprint, localCoinType, accountXpub);

        String changeRaw = String.format(
                "wpkh([%s/84h/%dh/0h]%s/1/*)",
                fingerprint, localCoinType, accountXpub);

        // ── 3. Verify they match watch-only wallet descriptors ────────────────
        JSONObject watchOnly  = (JSONObject) BitcoinRpcClient.executeRpc("listdescriptors");
        JSONArray  watchDescs = watchOnly.getJSONArray("descriptors");

        AppLogger.info(className, "[regtest] Watch-only descriptors:");
        for (int i = 0; i < watchDescs.length(); i++) {
            String d = watchDescs.getJSONObject(i).getString("desc");
            AppLogger.info(className, "[regtest]   {}", d);
        }
        AppLogger.info(className, "[regtest] Building receive: {}", recvRaw);
        AppLogger.info(className, "[regtest] Building change:  {}", changeRaw);

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
            AppLogger.info(className, "[regtest] Descriptors already present — skipping import.");
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
            AppLogger.info(className, "[regtest] Descriptor {} imported ✅",
                    i == 0 ? "receive" : "change");
        }

        // ── 7. DO NOT rescan here — mining hasn't happened yet ────────────────
        //    Rescan is called AFTER bootstrapRegtest() in the startup sequence
        AppLogger.info(className, "[regtest] Import complete — rescan will run after mining.");
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
        AppLogger.debug("mineBlocks(...) miningAddress: {}", miningAddress);
        CachedWalletMnemMap.cachedNewObject("miningAddressStatus", "used");
        CachedWalletMnemMap.cachedNewObject("blocks", nBlocks);
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
        System.out.println("4)  Hwi-based transaction"); 
        System.out.println("5)  Trezor-based transaction");
        System.out.println("6)  Mine blocks (regtest)");
        System.out.println("7)  Sync chain indexer now");
        System.out.println("8)  Run chain syncer now");
        System.out.println("9)  Merchant: receive payment");
        System.out.println("10) Merchant: receive payment (API/custom capacity)");
        System.out.println("11) Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }
    
    private void printCMMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Show wallet info");
        System.out.println("2)  Show fee rate estimate");
        System.out.println("3)  Send coins");
        System.out.println("4)  Hwi-based transaction");
        System.out.println("5)  Trezor-based transaction");
        System.out.println("6)  Mine blocks (regtest)");
        System.out.println("7)  Sync chain indexer now");
        System.out.println("8)  Run chain syncer now");
        System.out.println("9)  Customer make payment");
        System.out.println("10) Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }
    
    private void printODMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Show wallet info");
        System.out.println("2)  Show fee rate estimate");
        System.out.println("3)  Send coins");
        System.out.println("4)  Hwi-based transaction");
        System.out.println("5)  Trezor-based transaction");
        System.out.println("6)  Mine blocks (regtest)");
        System.out.println("7)  Sync chain indexer now");
        System.out.println("8)  Run chain syncer now");
        System.out.println("9)  Consolidate change UTXOs");
        System.out.println("0)  Exit application");
        System.out.print("Select option: ");
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 9 — Merchant receives payment (fixed capacity)
    // ─────────────────────────────────────────────────────────────────

    private void merchantReceivePayment() {
        try {
            int chainHeight = getBlockCount();
            AppLogger.info(className, "Starting merchant payment flow at height={}", chainHeight);

            merchantHandler.receivePaymentForGoods(chainHeight);

            AppLogger.info(className, "Payment received successfully.");
        } catch (PaymentException e) {
            AppLogger.error(className, "Payment failed at stage {}", "[" + e.getStage() + "]: "
                + e.getMessage());
        } catch (Exception e) {
            AppLogger.error(className, "Unexpected error: " + e.getMessage());
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
            AppLogger.info(className, "Starting API payment flow: capacity={} height={}",
                capacitySat, chainHeight);

            merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);

            AppLogger.info(className, "API payment received successfully.");
        } catch (PaymentException e) {
            AppLogger.error(className, "Payment failed at stage {}", "[" + e.getStage() + "]: "
                + e.getMessage());
        } catch (NumberFormatException e) {
            AppLogger.error(className, "Unexpected error: " + e.getMessage());
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
                AppLogger.warn(className, "Channel ID is required.");
                return;
            }

            int chainHeight = getBlockCount();
            AppLogger.info(className, "Starting customer payment: channelId={} height={}",
                channelId, chainHeight);

            customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);

            AppLogger.info(className, "Payment sent successfully.");           

        } catch (PaymentException e) {
            AppLogger.warn(className, "Payment failed: {}", e.getMessage());
        } catch (Exception e) {
            AppLogger.warn(className, "Unexpected error: {}", e.getMessage());
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
       
        System.out.println("\n=== UTXOs ===");
        System.out.println("UTXO count        : " + utxoCount);
        System.out.println("UTXO total amount : " + totalUtxoAmount + " BTC");

        // 3) Show a small sample of addresses being watched
        List<String> addrList = new ArrayList<>(addresses);
        
        System.out.println("\n=== Watched addresses (sample) ===");
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
        AppLogger.info(className, "=== Fee Estimates (BTC/kB and sat/vB) ===");

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
        AppLogger.info(className, "[sendCoins()] --- Send Coins ---");
        String destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("[sendCoins()] Invalid address for network " + network);
        }
                
        AppLogger.info(className, "Enter this addtress: {} as destination", destAddress);
        System.out.print("Destination address: ");
        String toAddress = scanner.nextLine().trim();
        if (toAddress.isEmpty()) {
            AppLogger.warn(className, "[sendCoins()] Destination address is required.");
            return;
        }

        System.out.print("Amount BTC (e.g. 0.1): ");
        String amountStr = scanner.nextLine().trim();
        if (amountStr.isEmpty()) {
            AppLogger.warn(className, "[sendCoins()] Amount is required.");
            return;
        }
        BigDecimal amountBtc = new BigDecimal(amountStr);

        System.out.print("Target blocks [6]: ");
        String targetStr = scanner.nextLine().trim();
        int targetBlocks = targetStr.isEmpty() ? 6 : Integer.parseInt(targetStr);

        AppLogger.info(className, "[sendCoins()] Creating, signing, and broadcasting transaction...");
        
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("[sendCoins()] Invalid address for network " + network);
        }
        
        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info(className, "[sendCoins] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info(className, "[sendCoins] Funding confirmed: {}", funding);

        if (funding.requiredMining()) {
            CachedWalletMnemMap.cachedNewObject("blocks", funding.roundsMined());
            
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", funding.roundsMined(), miningAddress);
            AppLogger.warn(className, "[sendCoins] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));        
                
        String txid = ownSigner.sendCoins(toAddress, amountBtc, changeAddress, targetBlocks, selectedUtxos);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);
        
        // ── Confirm on regtest ────────────────────────────────────────────────
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            CachedWalletMnemMap.cachedNewObject("blocks", 1);
            
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info(className, "[sendCoins] Mined 1 block to confirm tx.");
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }
       
        addrMgr.ensureLookahead();
                
        AppLogger.info(className, "[sendCoins()] Broadcasted txid: " + txid);
        AppLogger.info(className, "[sendCoins()] Verify in Core with:");
        AppLogger.info(className, "[sendCoins()] bitcoin-cli -regtest getrawtransaction " + txid + " 1");        
            
        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
        
        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            AppLogger.info(className, "[sendCoins()] usedReceiveAddress: {}", usedReceiveAddress);
        }
        
        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            AppLogger.info(className, "[sendCoins()] usedChangeAddress: {}", usedChangeAddress);    
        }        
    }
    
    private void sendHwiCoins() throws Exception {  
        Scanner scanner = new Scanner(System.in);
        AppLogger.info(className, "[sendHwiCoins()] --- Send Coins ---");
        String destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("[sendHwiCoins()] Invalid address for network " + network);
        }
                
        AppLogger.info(className, "[sendHwiCoins()] Enter this addtress: {} as destination", destAddress);
        System.out.print("Destination address: ");
        String toAddress = scanner.nextLine().trim();
        if (toAddress.isEmpty()) {
            AppLogger.warn(className, "[sendHwiCoins()] Destination address is required.");
            return;
        }

        System.out.print("Amount BTC (e.g. 0.1): ");
        String amountStr = scanner.nextLine().trim();
        if (amountStr.isEmpty()) {
            AppLogger.warn(className, "[sendHwiCoins()] Amount is required.");
            return;
        }
        BigDecimal amountBtc = new BigDecimal(amountStr);

        System.out.print("Target blocks [6]: ");
        String targetStr = scanner.nextLine().trim();
        int targetBlocks = targetStr.isEmpty() ? 6 : Integer.parseInt(targetStr);

        AppLogger.info(className, "[sendHwiCoins()] Creating, signing, and broadcasting transaction...");
        
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("[sendHwiCoins()] Invalid address for network " + network);
        }
        
        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info(className, "[sendHwiCoins()] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info(className, "[sendHwiCoins()] Funding confirmed: {}", funding);

        if (funding.requiredMining()) {
            CachedWalletMnemMap.cachedNewObject("blocks", funding.roundsMined());
            
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", funding.roundsMined(), miningAddress);
            AppLogger.warn(className, "[sendHwiCoins()] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));        
                
        String txid = ownSigner.sendHwiCoins(toAddress, amountBtc, changeAddress, targetBlocks, selectedUtxos);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);
        
        // ── Confirm on regtest ────────────────────────────────────────────────
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            CachedWalletMnemMap.cachedNewObject("blocks", 1);
            
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info(className, "[sendHwiCoins()] Mined 1 block to confirm tx.");
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }
       
        addrMgr.ensureLookahead();
                
        AppLogger.info(className, "[sendHwiCoins()] Broadcasted txid: " + txid);
        AppLogger.info(className, "[sendHwiCoins()] Verify in Core with:");
        AppLogger.info(className, "[sendHwiCoins()]   bitcoin-cli -regtest getrawtransaction " + txid + " 1");        
            
        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
        
        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            AppLogger.info(className, "[sendHwiCoins()] usedReceiveAddress: {}", usedReceiveAddress);
        }
        
        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            AppLogger.info(className, "[sendHwiCoins()] usedChangeAddress: {}", usedChangeAddress);    
        }        
    }
    
    private void sendTrezorCoins() throws Exception {  
        Scanner scanner = new Scanner(System.in);
        AppLogger.info(className, "[sendTrezorCoins()] --- Send Coins ---");
        String destAddress = addrMgr.getNextMiningAddress();
        if (!walletRpc.isValidCoreAddress(destAddress)) {
            throw new IllegalArgumentException("[sendTrezorCoins()] Invalid address for network " + network);
        }
                
        AppLogger.info(className, "[sendTrezorCoins()] Enter this addtress: {} as destination", destAddress);
        System.out.print("Destination address: ");
        String toAddress = scanner.nextLine().trim();
        if (toAddress.isEmpty()) {
            AppLogger.warn(className, "[sendTrezorCoins()] Destination address is required.");
            return;
        }

        System.out.print("Amount BTC (e.g. 0.1): ");
        String amountStr = scanner.nextLine().trim();
        if (amountStr.isEmpty()) {
            AppLogger.warn(className, "[sendTrezorCoins()] Amount is required.");
            return;
        }
        BigDecimal amountBtc = new BigDecimal(amountStr);

        System.out.print("Target blocks [6]: ");
        String targetStr = scanner.nextLine().trim();
        int targetBlocks = targetStr.isEmpty() ? 6 : Integer.parseInt(targetStr);

        AppLogger.info(className, "[sendTrezorCoins()] Creating, signing, and broadcasting transaction...");
        
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("[sendTrezorCoins()] Invalid address for network " + network);
        }
        
        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info(className, "[sendTrezorCoins()] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info(className, "[sendTrezorCoins()] Funding confirmed: {}", funding);

        if (funding.requiredMining()) {
            CachedWalletMnemMap.cachedNewObject("blocks", funding.roundsMined());
            
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", funding.roundsMined(), miningAddress);
            AppLogger.warn(className, "[sendTrezorCoins()] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));        
                
        String txid = ownSigner.sendTrezorCoins(toAddress, amountBtc, changeAddress, targetBlocks, selectedUtxos);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);
        
        // ── Confirm on regtest ────────────────────────────────────────────────
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            CachedWalletMnemMap.cachedNewObject("blocks", 1);
            
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info(className, "[sendTrezorCoins()] Mined 1 block to confirm tx.");
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }
       
        addrMgr.ensureLookahead();
                
        AppLogger.info(className, "[sendTrezorCoins()] Broadcasted txid: " + txid);
        AppLogger.info(className, "[sendTrezorCoins()] Verify in Core with:");
        AppLogger.info(className, "[sendTrezorCoins()] bitcoin-cli -regtest getrawtransaction " + txid + " 1");        
            
        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
        
        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            AppLogger.info(className, "[sendTrezorCoins()] usedReceiveAddress: {}", usedReceiveAddress);
        }
        
        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            AppLogger.info(className, "[sendTrezorCoins()] usedChangeAddress: {}", usedChangeAddress);    
        }        
    } 

    public int mineBlocks() throws Exception {
        if (!isRegtest()) {
            AppLogger.warn(className, "Mining is only supported on regtest in this console.");
            return 0;
        }
        
        Scanner scanner = new Scanner(System.in);
        System.out.print("How many blocks to mine? [1]: ");
        String in = scanner.nextLine().trim();
        int nBlocks = in.isEmpty() ? 1 : Integer.parseInt(in);

        AppLogger.info(className, "Mining {} block(s) on regtest...", nBlocks);
        var hashes = mineBlocks(nBlocks);  // calls BitcoinWalletApp.mineBlocks

        AppLogger.info(className, "Mined blocks:");
        for (String h : hashes) {
            AppLogger.info(className, "  " + h);
        }
        
        int height = getBlockCount();
        AppLogger.info(className, "Tip height is now (via Core): {}", height);
                
        AppLogger.info(className, "Current block height: {}",height); 
        return hashes.size();
    }
       
    private static void bootstrapChain(int numBlocks, String address) throws Exception {
        List<String> hashes = nodeRpc.generateToAddress(numBlocks, address);  
        
        if (!hashes.isEmpty()) {
            System.out.printf(
                "Mined %d block(s). First=%s Last=%s%n",
                hashes.size(),
                hashes.get(0).substring(0, 12),
                hashes.get(hashes.size() - 1).substring(0, 12)
            );
        } else {
            System.out.printf("Mined 0 block(s).%n");
        }
        
        BlockchainInfo blockchainInfo = walletRpc.getBlockchainInfo();
        AppLogger.info(className, blockchainInfo.toString());
    }
   
    private static String getNodeUsage() {
        String input;
        
        AppLogger.info(className, "\nNode usage mode setting."); 
        AppLogger.info(className, "Application includes merchant and customer functionalities.");
        AppLogger.info(className, "Select usage mode (merchant and customer) to enable on CLI menu.");
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
                    AppLogger.warn(className, "Invalid choice, please try again.");
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
                            sendHwiCoins();
                            break;
                        case 5:
                            sendTrezorCoins();
                            break;
                        case 6:
                            mineBlocks();
                            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);                                                        
                            break;
                        case 7:
                            chosenWorker = 2;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 8:
                            chosenWorker = 3;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 9:
                            merchantReceivePayment();
                            break;
                        case 10:
                            merchantGetPayment(scanner);
                            break;
                        case 11:
                            AppLogger.info(className, "--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    AppLogger.info(className, "✅ Consolidation complete!");
                                    AppLogger.info(className, "txid: {}", txid);
                                    AppLogger.info(className, "Your change UTXOs have been merged"
                                            + " into one spendable Utxo.");
                                    AppLogger.info(className, "Verify with:");
                                    AppLogger.info(className, "  bitcoin-cli -regtest"
                                            + " getrawtransaction {} 1", txid);

                                } else {
                                    AppLogger.info(className, "No change Utxos to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error(className, "[consolidate] Failed: {}", e.getMessage());
                            }
                            break;                        
                        case 0:
                            AppLogger.info(className, "Goodbye.");
                            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                AppLogger.info(className, " Shutdown hook — stopping ZMQ...");
                                //zmqSubscriber.stop();
                            }));
                            apisServer.stop();                            
                            trezor.disconnect();            
                            trezor.shutdown();
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
                            sendHwiCoins();
                            break;
                        case 5:
                            sendTrezorCoins();
                            break;
                        case 6:
                            mineBlocks();
                            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break; 
                        case 7:
                            chosenWorker = 2;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 8:
                            chosenWorker = 3;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 9:
                            makeCustomerPayment(scanner, publicKeyBytes);
                            break;
                        case 10:
                            AppLogger.info(className, "--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    AppLogger.info(className, "✅ Consolidation complete!");
                                    AppLogger.info(className, "txid: {}", txid);
                                    AppLogger.info(className, "Your change Utxos have been merged"
                                            + " into one spendable Utxo.");
                                    AppLogger.info(className, "Verify with:");
                                    AppLogger.info(className, "  bitcoin-cli -regtest"
                                            + " getrawtransaction {} 1", txid);

                                } else {
                                    AppLogger.info(className, "No change Utxos to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error(className, "[consolidate] Failed: {}", e.getMessage());
                            }
                            break;
                        case 0:
                            AppLogger.info(className, "Goodbye.");
                            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                AppLogger.info(className, " Shutdown hook — stopping ZMQ...");
                                //zmqSubscriber.stop();
                            }));
                            apisServer.stop();
                            trezor.disconnect();            
                            trezor.shutdown();
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
                            sendHwiCoins();
                            break;
                        case 5:
                            sendTrezorCoins();
                            break;
                        case 6:
                            mineBlocks();
                            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 7:
                            chosenWorker = 2;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 8:
                            chosenWorker = 3;   //(inputNum == 6) ? 1 : 2;
                            handleDelegation(chosenWorker);
                            break;
                        case 9:
                            AppLogger.info(className, "--- Consolidate Change UTXOs ---");
                            try {
                                String txid = ownSigner.convertChangeToSpendableUtxo();
                                if (txid != null) {
                                    AppLogger.info(className, "✅ Consolidation complete!");
                                    AppLogger.info(className, "txid: {}", txid);
                                    AppLogger.info(className, "Your change Utxos have been merged"
                                            + " into one spendable Utxo.");
                                    AppLogger.info(className, "Verify with:");
                                    AppLogger.info(className, "  bitcoin-cli -regtest"
                                            + " getrawtransaction {} 1", txid);

                                } else {
                                    AppLogger.info(className, "No change Utxos to consolidate.");
                                }
                            } catch (Exception e) {
                                AppLogger.error(className, "[consolidate] Failed: {}", e.getMessage());
                            }
                            break;
                        case 0:
                            AppLogger.info(className, "Goodbye.");
                            // In shutdown():
                            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                AppLogger.info(className, " Shutdown hook — stopping ZMQ...");
                                //zmqSubscriber.stop();
                            }));
                            apisServer.stop();
                            trezor.disconnect();            
                            trezor.shutdown();
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
                System.getLogger(HIDZMQHDWallet.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
        }
    }
    
    public static void handleDelegation(int workerId) {
        try {
            // 1. Wait until both workers are free
            while (monitor.isAnyWorkerBusy()) {
                // Sleep briefly so we don't print "Waiting..." 10,000 times a second
                // while the user isn't typing anything.
                Thread.sleep(200); 
            }

            // 2. Both are free! Make the decision.
            AppLogger.info(className, "Controller : Both Syncers are free. I will assign a task to a Syncer.");
            
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
        AppLogger.info(className, "[mineBlocksApi] Mined {} block(s) to {}", numBlocks, miningAddress);
        return numBlocks;
    }

    public String createMerchantInvoiceJson() throws IOException {
        // Example: take current invoice from lightningService or internal state.
        Invoice currentInvoice = lightningService.getCurrentInvoice(); // implement or adjust
        
        // Get current invoice (creates new one if expired)
        if (currentInvoice == null) {
            return "{\"error\":\"No active invoice\"}";
        }

        AppLogger.info(className, "Invoice: " + currentInvoice);
        AppLogger.info(className, "Amount: " + currentInvoice.getAmountSat() + " sat");
        AppLogger.info(className, "Payment Hash: " + HexUtils.bytesToHex(currentInvoice.getPaymentHash()));

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
            AppLogger.info(className, " Consolidated change, txid={}", txid);
        } else {
            AppLogger.info(className, " No change UTXOs to consolidate.");
        }
        return txid;
    }    
    
    // Pure logic: no Scanner, no System.in, suitable for HTTP APIs
    public String sendCoinsApi(String toAddress, BigDecimal amountBtc, int targetBlocks) throws Exception {
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

        AppLogger.info(className, "[sendCoinsApi] to={} amount={} targetBlocks={}", toAddress, amountBtc, targetBlocks);

        // Choose change address
        String changeAddress = addrMgr.getNextChangeAddress();
        if (!walletRpc.isValidCoreAddress(changeAddress)) {
            throw new IllegalArgumentException("Invalid change address for network " + network);
        }

        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info(className, "[sendCoinsApi] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info(className, "[sendCoinsApi] Funding confirmed: {}", funding);
        
        if (funding.requiredMining()) {
            CachedWalletMnemMap.cachedNewObject("blocks", funding.roundsMined());
            AppLogger.warn(className, "[sendCoinsApi] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
           
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            BitcoinRpcClient.executeRpc("generatetoaddress", funding.roundsMined(), miningAddress);
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));
        
        // First transaction: basic sendCoins(to, amount, change)
        String txid = ownSigner.sendCoins(toAddress, amountBtc, changeAddress, targetBlocks, selectedUtxos);
        addrMgr.markUsed(toAddress);
        addrMgr.markUsed(changeAddress);

        WalletAnalyzer walletAnalyzer = new WalletAnalyzer(walletRpc);
        walletAnalyzer.analyzeWallet();
        addrMgr.ensureLookahead();

        // Optional: auto‑mine confirmation on regtest
        if (AppNWKConfig.getInstance().isRegtest()) {
            String miningAddress = (String) CachedWalletMnemMap.getObject("miningAddress");
            CachedWalletMnemMap.cachedNewObject("blocks", 1);
            
            BitcoinRpcClient.executeRpc("generatetoaddress", 1, miningAddress);
            AppLogger.info(className, "[sendCoinsApi] Mined 1 block to confirm txs.");  
            
            int chosenWorker = 1;   //(inputNum == 6) ? 1 : 2;
            handleDelegation(chosenWorker);
        }

        Set<String> usedReceiveAddresses = walletAnalyzer.getReceiveAddresses();
        for (String usedReceiveAddress : usedReceiveAddresses) {
            AppLogger.info(className, "[sendCoinsApi] usedReceiveAddress: {}", usedReceiveAddress);
        }

        Set<String> usedChangeAddresses = walletAnalyzer.getChangeAddresses();
        for (String usedChangeAddress : usedChangeAddresses) {
            AppLogger.info(className, "[sendCoinsApi] usedChangeAddress: {}", usedChangeAddress);
        }

        return txid;
    }
    
    public ApisServer.ReceivePaymentResult receivePaymentApisWrapper(double capacitySat, int chainHeight) throws PaymentException {
        // Internally this method runs the flow you pasted
        merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);
        // If you store the last opened channelId somewhere, you can return it
        String lastChannelId = lightningService.getLastOpenedChannelId();
        return new ApisServer.ReceivePaymentResult(lastChannelId);
    }
    
    // Pure API method – no Scanner, ready for ApisServer
    public void makeCustomerPaymentApi(String channelId, byte[] pubKeyBytes) throws Exception {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("Channel ID is required.");
        }

        int chainHeight = getBlockCount();
        AppLogger.info(className, "Starting customer payment (API): channelId={} height={}",
            channelId, chainHeight);

        customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);
        AppLogger.info(className, "Customer payment via API succeeded.");
    }
    
    public static void main(String[] args) throws Exception {                    
        // Create the threads
        HIDZMQHDWallet nodeHDWallet = new HIDZMQHDWallet();          
            
        for (String addr : addrCache) {                
            if (!walletRpc.isValidCoreAddress(addr)) {
                throw new IllegalArgumentException("Invalid address for network " + network);
            }
        }            
               
        Thread regtestZmqListenerThread = new Thread(regtestZmqListener, "RegtestZmqListener");
        Thread chainIndexerThread = new Thread(chainIndexer, "chainIndexer");
        Thread chainSyncerThread = new Thread(chainSyncer, "chainSyncer");
        
        regtestZmqListenerThread.start();
        chainIndexerThread.start();
        chainSyncerThread.start();
        
        initializeWallet();
        
        // Controller starts and checks the variables
        nodeHDWallet.start();      
    }
}