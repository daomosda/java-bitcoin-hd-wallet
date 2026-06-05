package com.bitcoin.hdwallet.keymanagement;

import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import com.bitcoin.hdwallet.repository.MasterKeyRepository;
import com.bitcoin.hdwallet.model.MasterKeyRecord;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import java.nio.file.Path;

/**
 *
 * @author CONALDES
 */

public final class MasterKeyProvider {

    private static Bip32HDWallet masterKey;
    private static int masterFingerprint;
    private static Path repoPath;

    public static void init() throws Exception {
        repoPath = ConfigFilePaths.masterKeyPath();   //Path.of(
        //        System.getProperty("user.home"),
        //        ".mbbwallet", "node_m",
        //        "master_keys.json"
        //);
        
        boolean testnet = true;
        String network = "regtest";

        MasterKeyRepository repo = new MasterKeyRepository(repoPath);
        MasterKeyRecord rec = repo.getDefault();
        if (rec == null) {
            throw new IllegalStateException("No default master key in master_keys.json; run WalletInitMain once.");
        }
        if (!network.equals(rec.getNetwork())) {
            throw new IllegalStateException("Network mismatch: expected " + network + " but repo has " + rec.getNetwork());
        }

        Bip32HDWallet hd = Bip32HDWallet.fromBase58(rec.getXprvBase58());   //, testnet);
        masterKey = hd;
        masterFingerprint = hd.getFingerprintValue();
    }

    public static Bip32HDWallet getMasterKey() {
        return masterKey;
    }

    public static int getMasterFingerprint() {
        return masterFingerprint;
    }
    
    public static Path getRepopath() {
        return repoPath;
    }
}
