package com.bitcoin.hdwallet.repository;

import com.bitcoin.hdwallet.model.MasterKeyRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class MasterKeyRepository {

    public static final String DEFAULT_LABEL = "default";

    private final Path storePath;

    private final Map<String, MasterKeyRecord> byLabel = new HashMap<>();
    private final Map<Integer, MasterKeyRecord> byFingerprint = new HashMap<>();

    public MasterKeyRepository(Path storePath) throws IOException {
        this.storePath = storePath;
        load();
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    public Collection<MasterKeyRecord> listAll() {
        return Collections.unmodifiableCollection(byLabel.values());
    }

    public MasterKeyRecord findByLabel(String label) {
        return byLabel.get(label);
    }

    public MasterKeyRecord findByFingerprint(int fingerprint) {
        return byFingerprint.get(fingerprint);
    }

    /**
     * Add or replace a record by label.
     * If a record with the same label exists, it's overwritten.
     * @param rec
     * @throws java.io.IOException
     */
    public void put(MasterKeyRecord rec) throws IOException {
        byLabel.put(rec.getLabel(), rec);
        byFingerprint.put(rec.getFingerprint(), rec);
        save();
    }

    // Convenience for the single-master use case
    public MasterKeyRecord getDefault() {
        return findByLabel(DEFAULT_LABEL);
    }

    public void putDefault(MasterKeyRecord rec) throws IOException {
        if (!DEFAULT_LABEL.equals(rec.getLabel())) {
            rec = rec.withLabel(DEFAULT_LABEL);
        }
        put(rec);
    }

    // ─── Internal load/save ────────────────────────────────────────────────────

    private void load() throws IOException {
        byLabel.clear();
        byFingerprint.clear();

        if (!Files.exists(storePath)) {
            return; // nothing to load yet
        }

        String json = Files.readString(storePath);
        if (json.isEmpty()) {
            return;
        }

        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("masters");
        if (arr == null) {
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String label = o.getString("label");
            String network = o.getString("network"); // "mainnet", "testnet", "regtest"
            String xprv = o.getString("xprv");
            String fpHex = o.getString("fingerprint");
            int fp = (int) Long.parseLong(fpHex, 16);

            MasterKeyRecord rec = new MasterKeyRecord(label, network, xprv, fp);
            byLabel.put(label, rec);
            byFingerprint.put(fp, rec);
        }
    }
    
    public MasterKeyRecord loadMKRecord() throws IOException {
        String json = Files.readString(storePath);
        
        MasterKeyRecord rec = null;
        if (Files.exists(storePath) && !json.isEmpty()) {

            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("masters");
            if (arr != null) {
                
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String label = o.getString("label");
                    String network = o.getString("network"); // "mainnet", "testnet", "regtest"
                    String xprv = o.getString("xprv");
                    String fpHex = o.getString("fingerprint");
                    int fp = (int) Long.parseLong(fpHex, 16);

                    rec = new MasterKeyRecord(label, network, xprv, fp);
                }
            }
        }
        return rec;
    }

    private void save() throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray arr = new JSONArray();

        for (MasterKeyRecord rec : byLabel.values()) {
            JSONObject o = new JSONObject();
            o.put("label", rec.getLabel());
            o.put("network", rec.getNetwork());
            o.put("xprv", rec.getXprvBase58());
            o.put("fingerprint", String.format("%08x", rec.getFingerprint()));
            arr.put(o);
        }

        root.put("masters", arr);

        Files.createDirectories(storePath.getParent());
        Files.writeString(storePath, root.toString(2));
    }    
}