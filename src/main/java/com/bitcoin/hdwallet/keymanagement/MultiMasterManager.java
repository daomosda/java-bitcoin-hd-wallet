package com.bitcoin.hdwallet.keymanagement;

import com.bitcoin.hdwallet.repository.MasterKeyRepository;
import com.bitcoin.hdwallet.model.MasterKeyRecord;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author CONALDES
 */

public final class MultiMasterManager {

    private final MasterKeyRepository repo;
    private final Map<Integer, Bip32HDWallet> masterByFp = new HashMap<>();

    public MultiMasterManager(Path storePath) throws IOException {
        this.repo = new MasterKeyRepository(storePath);
        loadAll();
    }

    private void loadAll() {
        masterByFp.clear();
        for (MasterKeyRecord rec : repo.listAll()) {
            boolean testnet = !"mainnet".equals(rec.getNetwork());
            Bip32HDWallet hd = Bip32HDWallet.fromBase58(rec.getXprvBase58());  //, testnet);
            int fp = hd.getFingerprintValue();
            masterByFp.put(fp, hd);
        }
    }

    public Bip32HDWallet findMasterByFingerprint(int fp) {
        return masterByFp.get(fp);
    }

    public Set<Integer> listFingerprints() {
        return masterByFp.keySet();
    }

    public void addNewMaster(String label, String network, byte[] seed) throws Exception {
        boolean testnet = !"mainnet".equals(network);
        Bip32HDWallet master = Bip32HDWallet.fromSeed(seed, testnet);
        String xprv = master.toBase58(testnet, true);
        int fp = master.getFingerprintValue();

        MasterKeyRecord rec = new MasterKeyRecord(label, network, xprv, fp);
        repo.put(rec);    //addOrReplace(rec); // if label exists, update; else add
        masterByFp.put(fp, master);
    }
}