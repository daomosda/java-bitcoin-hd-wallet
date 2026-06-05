package com.bitcoin.hdwallet.keymanagement;

import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import com.bitcoin.hdwallet.core.Bip39Mnemonic;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.crypto.SegWitAddress;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.MAINNET;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.REGTEST;
import static com.bitcoin.hdwallet.crypto.SegWitAddress.Network.TESTNET;
import com.bitcoin.hdwallet.model.MasterKeyRecord;
import com.bitcoin.hdwallet.repository.MasterKeyRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 *
 * @author CONALDES
 */

public final class HdWalletService {
    
    public static Bip32HDWallet createBip32HDWallet(SegWitAddress.Network network, String existingMnemonic) throws IOException, Exception {
        String networkName = "";
        
        boolean isMainnet;
        boolean isTestnet = false;
        boolean isRegtest = false;
        boolean testnet = false;  
        switch (network) {
            case MAINNET -> {
                isMainnet = true;
                testnet = false;
                networkName = "mainnet";
            }
            case TESTNET -> {
                isTestnet = true;
                testnet = true;
                networkName = "testnet";
            }
            case REGTEST -> {
                isRegtest = true;
                testnet = false;
                networkName = "regtest";
            }
        }    
       
        Path seedFile = ConfigFilePaths.masterSeedPath();    //Path.of(
        //        System.getProperty("user.home"),
        //        ".mbbwallet", "node_m",
        //        "wallet_seed.json"
        //);
        
        Path repoPath = ConfigFilePaths.masterKeyPath();   //Path.of(
        //        System.getProperty("user.home"),
        //        ".mbbwallet", "node_m",
        //        "master_keys.json"
        //);
        
        if (Files.exists(repoPath)
            && Files.isRegularFile(repoPath)
            && Files.isReadable(repoPath)) {

            System.out.println("Master key repository found");

            WalletSeedStore.WalletSeedData data = 
                    WalletSeedStore.loadWalletSeed(seedFile);

            boolean testnetFlag = data.isTestnetFlag();

            byte[] seed = data.getSeed();
            Bip32HDWallet masterKey = Bip32HDWallet.fromSeed(seed, testnetFlag);
          
            MasterKeyProvider.init();
            int masterFingerprint =  MasterKeyProvider.getMasterFingerprint();
            
            Bip32HDWallet hdMasterKey = MasterKeyProvider.getMasterKey();
            MasterKeyRepository repo = new MasterKeyRepository(repoPath);
            MasterKeyRecord masterKeyRecord = repo.loadMKRecord();
            CachedWalletMnemMap.cachedNewObject("masterKey", masterKey);
            CachedWalletMnemMap.cachedNewObject("DEFAULT_LABEL", masterKeyRecord.getLabel());
            CachedWalletMnemMap.cachedNewObject("networkName", masterKeyRecord.getNetwork());
            CachedWalletMnemMap.cachedNewObject("xprv", masterKeyRecord.getXprvBase58());
            CachedWalletMnemMap.cachedNewObject("fp", masterKeyRecord.getFingerprint());

            return hdMasterKey;
        } else {

            System.out.println("Master key repository missing");
            
            // 8) BIP39 mnemonic
            Bip39Mnemonic mnemonic;
            if (existingMnemonic != null && !existingMnemonic.isBlank()) {
                mnemonic = Bip39Mnemonic.fromWords(Arrays.asList(existingMnemonic.trim().split("\\s+")));
                AppLogger.info("Restored wallet from existing mnemonic");
            } else {
                mnemonic = Bip39Mnemonic.generate(21);
                AppLogger.info("Generated new 21-word mnemonic: {}", mnemonic.toString());
            }

            // 9) BIP32 master key (testnet/regtest aware)
            boolean testnetFlag = isTestnet || isRegtest;
            byte[] seed = mnemonic.toSeed("");
            Bip32HDWallet masterKey = Bip32HDWallet.fromSeed(seed, testnetFlag); 

            WalletSeedStore.saveWalletSeed(
                    seedFile,
                    testnetFlag,
                    seed
            );

            // 2. Get the extended private key (tprv/xprv)
            String xprv = masterKey.toBase58(testnet, false);  // false => include private key

            // 3. Get the master fingerprint as an int
            int fp = masterKey.getFingerprintValue();

            // 4. Convert fingerprint to 8‑char hex string
            String fpHex = String.format("%08x", fp);

            System.out.println("xprv/tprv = " + xprv);
            System.out.println("fingerprint = " + fpHex);
                         
            MasterKeyRepository repo = new MasterKeyRepository(repoPath);

            // Use the values we just computed
            MasterKeyRecord rec =
                    new MasterKeyRecord(
                            MasterKeyRepository.DEFAULT_LABEL, // "default"
                            networkName,                       // "regtest"
                            xprv,                              // e.g. "tprv8ZgxMBicQKsPe..."
                            fp                                 // e.g. 0x5aba3954
                    );

            repo.putDefault(rec);
        
            System.out.println(
                    "Created master key repository: " + repoPath
            );
            
            CachedWalletMnemMap.cachedNewObject("masterKey", masterKey);
            CachedWalletMnemMap.cachedNewObject("DEFAULT_LABEL", MasterKeyRepository.DEFAULT_LABEL);
            CachedWalletMnemMap.cachedNewObject("networkName", networkName);
            CachedWalletMnemMap.cachedNewObject("xprv", xprv);
            CachedWalletMnemMap.cachedNewObject("fp", fp);
              
            return masterKey;
        }
    }    
    
    public static Bip32HDWallet retrieveMasterKey() throws IOException {
        Path seedFile = ConfigFilePaths.masterSeedPath();   //Path.of(
        //        System.getProperty("user.home"),
        //        ".mbbwallet", "node_m",
        //        "wallet_seed.json"
        //);
        
        WalletSeedStore.WalletSeedData data = 
                WalletSeedStore.loadWalletSeed(seedFile);

        boolean testnetFlag = data.isTestnetFlag();

        byte[] seed = data.getSeed();
        Bip32HDWallet masterKey = Bip32HDWallet.fromSeed(seed, testnetFlag);
        return masterKey;
    }
}