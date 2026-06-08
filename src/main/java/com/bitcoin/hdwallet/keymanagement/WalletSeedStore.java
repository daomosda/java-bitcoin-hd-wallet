package com.bitcoin.hdwallet.keymanagement;

/**
 *
 * @author DAOMOSDA
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.json.JSONObject;

public class WalletSeedStore {

    // ─────────────────────────────────────────────
    // Save wallet seed + network flag
    // ─────────────────────────────────────────────

    public static void saveWalletSeed(
            Path filePath,
            boolean testnetFlag,
            byte[] seed
    ) throws IOException {

        // Ensure parent directory exists
        Path parent = filePath.getParent();

        if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }

        JSONObject obj = new JSONObject();

        obj.put("testnetFlag", testnetFlag);

        // Store seed safely as Base64
        obj.put(
                "seedBase64",
                Base64.getEncoder().encodeToString(seed)
        );

        Files.writeString(
                filePath,
                obj.toString(2),
                StandardCharsets.UTF_8
        );

        System.out.println(
                "[WalletSeedStore] Seed saved: " + filePath
        );
    }

    // ─────────────────────────────────────────────
    // Load wallet seed + network flag
    // ─────────────────────────────────────────────

    public static WalletSeedData loadWalletSeed(
            Path filePath
    ) throws IOException {

        if (Files.notExists(filePath)) {
            throw new IOException(
                    "Wallet seed file not found: " + filePath
            );
        }

        String json = Files.readString(
                filePath,
                StandardCharsets.UTF_8
        );

        JSONObject obj = new JSONObject(json);

        boolean testnetFlag =
                obj.getBoolean("testnetFlag");

        String seedBase64 =
                obj.getString("seedBase64");

        byte[] seed =
                Base64.getDecoder().decode(seedBase64);

        System.out.println(
                "[WalletSeedStore] Seed loaded: " + filePath
        );

        return new WalletSeedData(
                testnetFlag,
                seed
        );
    }

    // ─────────────────────────────────────────────
    // Result holder
    // ─────────────────────────────────────────────

    public static class WalletSeedData {

        private final boolean testnetFlag;

        private final byte[] seed;

        public WalletSeedData(
                boolean testnetFlag,
                byte[] seed
        ) {
            this.testnetFlag = testnetFlag;
            this.seed = seed;
        }

        public boolean isTestnetFlag() {
            return testnetFlag;
        }

        public byte[] getSeed() {
            return seed;
        }
    }
}