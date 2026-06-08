package com.bitcoin.hdwallet.keymanagement;

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.crypto.SegWitAddress;
import com.bitcoin.hdwallet.crypto.SegWitAddress.Network;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.MAINNET;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.REGTEST;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.TESTNET;
import com.bitcoin.hdwallet.model.HDKey;
import com.bitcoin.hdwallet.repository.HDKeyRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */

public class HdAddressManager {
  
    private final Bip32HDWallet master;
    private final int purpose;
    private final int coinType;
    private final int account;
    private int receiveIndex = 0;
    //private int changeIndex = 0;
    private boolean testnet;
    private SegWitAddress.Network network;
    private final HDKeyRepository repository;
    private static final int DERIVE_BATCH_SIZE = 10;
    public static final int DERIVE_LOOKAHEAD = 5;
    private static final int CHANGE_BATCH_SIZE = 5;
    public static final int CHANGE_LOOKAHEAD = 3;
    private final BitcoinRpcClient rpc;
    //private final BitcoinRpcClient walletRpc;
    private int fingerprintValue;
    
    private static int one_time;

    public HdAddressManager(Bip32HDWallet master, SegWitAddress.Network network, 
            HDKeyRepository repository, BitcoinRpcClient rpc/*, BitcoinRpcClient walletRpc*/) throws SQLException, IOException {  //boolean testnet) {
        this.master = master;
        this.network = network;
        this.repository = repository;
        this.rpc = rpc;
        //this.walletRpc = walletRpc;
        HdAddressManager.one_time = 1;
                
        boolean isMainnet = false;
        boolean isTestnet = false;
        boolean isRegtest = false;

        switch (network) {
            case MAINNET -> isMainnet = true;
            case TESTNET -> isTestnet = true;
            case REGTEST -> isRegtest = true;
        }

        this.testnet = isTestnet || isRegtest;  
        this.purpose = 84;
        this.coinType = testnet ? 1 : 0;
        this.account = 0;
        this.receiveIndex = repository.getNextIndex();
    }

    private String makePath(boolean change, int index) {
        // m / purpose' / coinType' / account' / change / index
        int changeComponent = change ? 1 : 0;
        return String.format("m/%d'/%d'/%d'/%d/%d",
                purpose, coinType, account, changeComponent, index);
    }

    private String nextHDWalletAddress(boolean change) throws IOException, SQLException {
        String receivePath = makePath(change, receiveIndex++);
        Bip32HDWallet receiveChild = master.derivePath(receivePath);
        byte[] publicKey = receiveChild.getPublicKeyCompressed();
        byte[] privateKey = receiveChild.getPrivateKeyBytes();
            
        String accountXpub = receiveChild.toBase58(testnet, true);
                
        String fingerprintHex = receiveChild.getFingerprintHex();
        int fingerprinValue = receiveChild.getFingerprintValue();        
        
        String address = SegWitAddress.fromPubKeyCompressed(publicKey, "p2wpkh", this.network); 
        
        //AppLogger.debug("[HdAddressManager => nextHDWalletAddress()] address = {}, accountXpub = {}, fingerprintHex = {}",
        //        address, accountXpub.substring(0, 20), fingerprintHex);
        
        HDKey hdKey = new HDKey(privateKey, publicKey, receivePath, accountXpub, 
                fingerprintHex, fingerprinValue, receiveIndex, change);
        repository.save(hdKey);
        fingerprintValue = repository.getFirstFingerPrintValue();
        return address;
    }
        
    public String getNextMiningAddress() throws Exception {
        List<String> derivedaddrs = new ArrayList<>();
        int countUnusedKeys = repository.countUnusedMiningKeys();
        
        String address;
        if (countUnusedKeys < DERIVE_LOOKAHEAD) {
            for (int i = 0; i < DERIVE_BATCH_SIZE; i++) {
                address = nextHDWalletAddress(false);
                derivedaddrs.add(address);
            }   
            importIntoCore(derivedaddrs);
        }
        countUnusedKeys = repository.countUnusedMiningKeys();
        AppLogger.info("[HdAddressManager => getNextMiningAddress()] countUsedKeys={}, LOOKAHEAD={}",
                countUnusedKeys, DERIVE_LOOKAHEAD);
        HDKey hdKey = repository.pickMiningKey();
        if (hdKey != null) {
            return hdKey.getAddress();
        }
       
        countUnusedKeys = repository.countUnusedMiningKeys();
        AppLogger.info("[HdAddressManager => getNextMiningAddress()] countUsedKeys={}, LOOKAHEAD={}",
                countUnusedKeys, DERIVE_LOOKAHEAD);
        return null;    
    }
    
    public String getInternalZpub() {
        // 1. Use the DERIVATION method you found in Step 1 with index 1 (Change)
        //    Note: Pass '1' for the internal/change chain.
        Bip32HDWallet internalChainKey = master.deriveChild(1);
        // 2. Use the SERIALIZATION method you found in Step 2
        //    This converts the key object into the "zpub..." string.
        return internalChainKey.toBase58(testnet, false);  //toBase58(boolean testnet,boolean publicOnly)
    }
    
    public String getNextChangeAddress() throws Exception {
        List<String> derivedaddrs = new ArrayList<>();
        int countUnusedKeys = repository.countUnusedChangeKeys();
        
        String address;
        if (countUnusedKeys < CHANGE_LOOKAHEAD) {
            for (int i = 0; i < CHANGE_BATCH_SIZE; i++) {
                address = nextHDWalletAddress(true);
                derivedaddrs.add(address);
            }   
            importIntoCore(derivedaddrs);
        }
        countUnusedKeys = repository.countUnusedChangeKeys();
        AppLogger.info("[HdAddressManager => getNextChangeAddress()] countUnusedChangeKeys={}, CHANGE_LOOKAHEAD={}",
                countUnusedKeys, CHANGE_LOOKAHEAD);
        HDKey hdKey = repository.pickChangeKey();
        if (hdKey != null) {
            return hdKey.getAddress();
        }
       
        countUnusedKeys = repository.countUnusedChangeKeys();
        AppLogger.info("[HdAddressManager => getNextChangeAddress()] countUnusedChangeKeys={}, CHANGE_LOOKAHEAD={}",
                countUnusedKeys, CHANGE_LOOKAHEAD);
        return null;     
    }
        
    public HDKey hdKeyFromAddress(String address) throws SQLException, IOException {
        return repository.hdKeyByAddress(address).get();
    } 
    
    public List<String> getAllAddresses() {
         return repository.findAllAddresses();
    }
    
    public List<String> getAllMiningAddresses() {
         return repository.findMiningAddresses();
    }
    
    public List<String> getAllChangeAddresses() {
         return repository.findChangeAddresses();
    }
    
    public void markUsed(String address) throws IOException, SQLException {
        repository.markAddressUsed(address);
    }
    
    public List<String> getUsedChangeAddresses() throws SQLException {
        return repository.getUsedChangeAddresses();
    }
            
    public void ensureLookahead() throws Exception {

        // ── Step a+c: receive addresses ───────────────────────────────────
        int unusedKeys = repository.countUnusedMiningKeys();
        AppLogger.info("[HdAddressManager] Lookahead check: unused receive={} threshold={}",
            unusedKeys, DERIVE_LOOKAHEAD);

        if (unusedKeys < DERIVE_LOOKAHEAD) {
            // calculate exactly how many we need to bring pool up to LOOKAHEAD
            int needed = DERIVE_LOOKAHEAD - unusedKeys;
            AppLogger.info("[HdAddressManager] Receive pool low — deriving {} more addresses",
                needed);

            // derive `needed` addresses and save them to DB with used=0
            List<String> derivedaddrs = new ArrayList<>();
            String address;
            for (int i = 0; i < DERIVE_BATCH_SIZE; i++) {
                address = nextHDWalletAddress(false);
                derivedaddrs.add(address);
                AppLogger.info("[HdAddressManager =>ensureLookahead()] Address derived = {}",
                address);  
            }  

            // import them into Core's watch-only wallet immediately
            // so Core starts scanning for transactions to these addresses
            importIntoCore(derivedaddrs);
        }

        AppLogger.info("[HdAddressManager] Lookahead OK: receive={}",
            repository.countUnusedMiningKeys());
    }
    
    // ─────────────────────────────────────────────────────────────────────────────
    
    private void importIntoCore(List<String> addresses) throws Exception {
        if (addresses.isEmpty()) return;

        AppLogger.info("[HdAddressManager => importIntoCore] network={} coinType={}", network, coinType);
        
        AppLogger.info("[HdAddressManager => importIntoCore] Importing {} addresses into watch-only wallet...",
                addresses.size());

        JSONArray descriptors = new JSONArray();

        for (String address : addresses) {
            JSONObject entry = buildImportEntry(address);
            if (entry != null) {
                descriptors.put(entry);
            }
        }

        if (descriptors.length() == 0) {
            AppLogger.warn("[HdAddressManager] No valid descriptors built — skipping import.");
            return;
        }

        Object result = BitcoinRpcClient.executeRpc("importdescriptors", descriptors);

        if (!(result instanceof JSONArray results)) {
            AppLogger.info("[HdAddressManager => importIntoCore] importdescriptors unexpected result: {}", result);
            return;
        }

        int success = 0;
        int failed  = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject entry   = results.getJSONObject(i);
            boolean    ok      = entry.optBoolean("success", false);
            JSONObject error   = entry.optJSONObject("error");
            if (ok) {
                success++;
            } else {
                failed++;
                String errMsg = (error != null)
                        ? error.optString("message", "Unknown error")
                        : "Unknown error";
                AppLogger.error("[HdAddressManager] importdescriptors [{}] failed: {}",
                        i, errMsg);
            }
        }
        AppLogger.info("[HdAddressManager] Import complete: {} success {} failed",
                success, failed);
    }
    
    // ─────────────────────────────────────────────────────────────────────────────
  
    /**
     * Looks up the derivation path for an address from your DB,
     * then derives the pubkey from the master key.
     *
     * Adjust hdKeyRepository.getDerivationPath() to match your actual DB method.
     */
    private String derivePubkeyForAddress(String address) throws Exception {
        // Look up derivation path stored when address was generated
        // e.g. "m/84'/1'/0'/0/3"
        HDKey hdKey = repository.hdKeyByAddress(address).get();
        String path = hdKey.getPath();   //repository.getDerivationPath(address);
        if (path == null || path.isBlank()) {
            AppLogger.warn("[HdAddressManager] No derivation path in DB for: {}",
                    address);
            return null;
        }

        AppLogger.info("[HdAddressManager] Deriving pubkey for {} at path {}",
                address, path);

        // Derive the key at this path from master
        //Bip32HDWallet childKey = masterKey.derivePath(path);
        byte[]        pubBytes = hdKey.getPubKeyBytes();   //childKey.getPublicKeyBytes();
        return HexFormat.of().formatHex(pubBytes); // 33-byte compressed pubkey hex
    }    
      
    private JSONObject buildImportEntry(String address) throws Exception {

        // ── Get key record from DB ────────────────────────────────────────────
        HDKey hdKey = repository.hdKeyByAddress(address).get();
        if (hdKey == null) {
            AppLogger.warn("[HdAddressManager => buildImportEntry] No key record for {} — skipping", address);
            return null;
        }

        // ── Derive account xpub ───────────────────────────────────────────────
        
        String accountXpub = hdKey.getAccountXpub();        
        String fingerprint = String.format("%08x", fingerprintValue);        
        //String fingerprintHex  = hdKey.getFingerprintHex();
         
        int changeComponent = hdKey.isChange() ? 1 : 0;
        int index           = hdKey.getKeyIndex();

        // ── Build wpkh([fingerprint/path]xpub/change/index) ──────────────────
        // ✅ This embeds full HD path — Core can build correct bip32_derivs
        String raw = String.format(
                "wpkh([%s/84h/%dh/0h]%s/%d/%d)",
                fingerprint, coinType, accountXpub,
                changeComponent, index);

        AppLogger.info("[import] Building descriptor for {}: {}", address,
                raw.substring(0, 40) + "...");

        String checksummed = rpc.getDescriptorWithChecksum(raw);

        boolean isInternal = hdKey.isChange();

        JSONObject entry = new JSONObject()
                .put("desc",      checksummed)
                .put("timestamp", 0)
                .put("internal",  isInternal)
                .put("active",    false);

        // ✅ No label for internal (change) addresses
        if (!isInternal) {
            entry.put("label", "lookahead");
        }

        return entry;
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds wpkh([fingerprint/path]xpub/index) from address metadata.
     * Used when pubkey bytes are not directly accessible but xpub and
     * index are stored in DB.
     */
    private String buildWpkhFromDerivationPath(String address) throws Exception {
        // Get index and change flag from DB
        HDKey keyRecord = repository.hdKeyByAddress(address).get();;   //hdKeyRepository.getKeyRecord(address);
        
        if (keyRecord == null) return null;

        //Network localNetwork            = this.network;   //AppNWKConfig.getInstance().getNetwork();
        boolean useTestnetEncoding = (this.network == Network.TESTNET
                                   || this.network == Network.REGTEST);
        //int     coinType           = useTestnetEncoding ? 1 : 0;

        // Derive account xpub
        Bip32HDWallet account84 = master.derivePath(
                String.format("m/84'/%d'/0'", this.coinType));
        String accountXpub      = account84.toBase58(useTestnetEncoding, true);
        String fingerprint      = String.format("%08x",
                master.getFingerprintValue());

        // change=0 for receive, change=1 for change addresses
        int changeComponent = keyRecord.isChange() ? 1 : 0;
        int index           = keyRecord.getKeyIndex();

        // wpkh([fingerprint/84h/1h/0h]xpub/change/index)
        String raw = String.format(
                "wpkh([%s/84h/%dh/0h]%s/%d/%d)",
                fingerprint, coinType, accountXpub, changeComponent, index);

        return rpc.getDescriptorWithChecksum(raw);
    }
}
