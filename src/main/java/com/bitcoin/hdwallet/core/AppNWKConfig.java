package com.bitcoin.hdwallet.core;

import com.bitcoin.hdwallet.crypto.SegWitAddress.Network;

/**
 *
 * @author DAOMOSDA
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AppNWKConfig {

    private static final AppNWKConfig INSTANCE = new AppNWKConfig();

    private final Network network;

    // Extra config fields from bitcoin.conf
    private final boolean server;
    private final String rpcUser;
    private final String rpcPassword;
    private final String rpcAllowIp;
    private final int rpcMainPort;
    private final int rpcTestPort;
    private final int rpcRegPort;

    private final String backend;         // electrum / esplora / etc. (optional)
    private final String electrumHost;
    private final int electrumPort;
    private final boolean electrumSsl;

    private final String esploraUrl;

    private final String walletName;
    private final String watchOnlyWalletName;

    private final String keystorePath;
    private final String rpcStorePass;
    private final String rpcKeyPass;
    private final String apiStorePass;
    private final String apiKeyPass;
    
    private boolean isMainnet = false;
    private boolean isTestnet = false;
    private boolean isRegtest = false;

    private AppNWKConfig() {
        // 1) Read all key=value into a map (if bitcoin.conf exists)
        Map<String, String> conf = readConfigFromBitcoinConf();

        // 2) Resolve network from config, then fallback to system/env
        String netFromConf = null;

        // Prefer explicit network keys from file if present
        if (conf != null) {
            // New style: network=regtest / mainnet / testnet
            String networkKey = conf.get("network");
            if (networkKey != null && !networkKey.isBlank()) {
                netFromConf = networkKey.trim();
            }

            // Older style flags: regtest=1, testnet=1
            if (netFromConf == null) {
                if ("1".equals(conf.get("regtest"))) {
                    netFromConf = "regtest";
                } else if ("1".equals(conf.get("testnet"))) {
                    netFromConf = "testnet";
                }
            }

            // btc.network override in bitcoin.conf (same name as system property)
            if (netFromConf == null) {
                String btcNetwork = conf.get("btc.network");
                if (btcNetwork != null && !btcNetwork.isBlank()) {
                    netFromConf = btcNetwork.trim();
                }
            }
        }

        String net = (netFromConf != null)
                ? netFromConf
                : System.getProperty(
                        "btc.network",
                        System.getenv().getOrDefault("BTC_NETWORK", "regtest")
                  );

        switch (net.toLowerCase(Locale.ROOT)) {
            case "main", "mainnet" -> {
                network = Network.MAINNET;
                isMainnet = true;
            }  
            case "test", "testnet" -> {
                network = Network.TESTNET;
                isTestnet = true;
            }
            case "regtest" -> {
                network = Network.REGTEST;
                isRegtest = true;
            }
            default -> throw new IllegalArgumentException("Unknown btc.network: " + net);
        }

        // 3) Initialize extra fields from conf map (with defaults)
        if (conf != null) {
            server           = parseBoolean(conf.get("server"), true);
            rpcUser          = conf.getOrDefault("rpcuser", "olamosda");
            rpcPassword      = conf.getOrDefault("rpcpassword", "cona.btc-Dao_5MoS7");
            rpcAllowIp       = conf.getOrDefault("rpcallowip", "127.0.0.1");

            rpcMainPort      = parseInt(conf.get("rpcmainport"), 8332);
            rpcTestPort      = parseInt(conf.get("rpctestport"), 18332);
            rpcRegPort       = parseInt(conf.get("rpcregport"), 18443);

            backend          = conf.getOrDefault("backend", "").trim();
            electrumHost     = conf.getOrDefault("electrum.host", "").trim();
            electrumPort     = parseInt(conf.get("electrum.port"), 50002);
            electrumSsl      = parseBoolean(conf.get("electrum.ssl"), true);

            esploraUrl       = conf.getOrDefault("esplora.url", "").trim();

            walletName       = conf.getOrDefault("wallet.name", "wallet");
            watchOnlyWalletName = conf.getOrDefault("watchonly.wallet.name", "watchonly");

            keystorePath     = conf.getOrDefault("keystorePath", "").trim();
            rpcStorePass     = conf.getOrDefault("rpcStorePass", "").trim();
            rpcKeyPass       = conf.getOrDefault("rpcKeyPass", "").trim();
            apiStorePass     = conf.getOrDefault("apiStorePass", "").trim();
            apiKeyPass       = conf.getOrDefault("apiKeyPass", "").trim();
        } else {
            // Defaults if bitcoin.conf not found
            server           = true;
            rpcUser          = "olamosda";
            rpcPassword      = "cona.btc-Dao_5MoS7";
            rpcAllowIp       = "127.0.0.1";

            rpcMainPort      = 8332;
            rpcTestPort      = 18332;
            rpcRegPort       = 18443;

            backend          = "";
            electrumHost     = "electrum.blockstream.info";
            electrumPort     = 50002;
            electrumSsl      = true;

            esploraUrl       = "https://blockstream.info/testnet/api";

            walletName       = "daowallet";
            watchOnlyWalletName = "btcnode_watchonly";

            keystorePath     = "C:\\\\Users\\\\CONALDES\\\\btc-server-keystore-cert\\\\rpcbtcserver.jks";
            rpcStorePass     = "stkyp_BTC@57";
            rpcKeyPass       = "stkyp_BTC@57";
            apiStorePass     = "tkyp_57@BTC";
            apiKeyPass       = "stkyp_57@BTC";
        }
      
        AppLogger.info("[AppNWKConfig] Network resolved: {}", network);
        AppLogger.info("[AppNWKConfig] wallet.name={}, watchonly.wallet.name={}", walletName, watchOnlyWalletName);
    }

    public static AppNWKConfig getInstance() {
        return INSTANCE;
    }

    public Network getNetwork() {
        return network;
    }

    public boolean isServer() {
        return server;
    }
    
    public boolean isMainnet() {
        return isMainnet;
    }
    
    public boolean isTestnet() {
        return isTestnet;
    }
    
    public boolean isRegtest() {
        return isRegtest;
    }

    public String getRpcUser() {
        return rpcUser;
    }

    public String getRpcPassword() {
        return rpcPassword;
    }

    public String getRpcAllowIp() {
        return rpcAllowIp;
    }

    public int getRpcPort() {
        return switch (network) {
            case MAINNET -> rpcMainPort;
            case TESTNET -> rpcTestPort;
            case REGTEST -> rpcRegPort;
        };
    }

    public String getBackend() {
        return backend;
    }

    public String getElectrumHost() {
        return electrumHost;
    }

    public int getElectrumPort() {
        return electrumPort;
    }

    public boolean isElectrumSsl() {
        return electrumSsl;
    }

    public String getEsploraUrl() {
        return esploraUrl;
    }

    public String getWalletName() {
        return walletName;
    }

    public String getWatchOnlyWalletName() {
        return watchOnlyWalletName;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public String getRpcStorePass() {
        return rpcStorePass;
    }

    public String getRpcKeyPass() {
        return rpcKeyPass;
    }

    public String getApiStorePass() {
        return apiStorePass;
    }

    public String getApiKeyPass() {
        return apiKeyPass;
    }

    // ===================== Helpers =====================

    /**
     * Read bitcoin.conf into a simple key -> value map (lowercase keys).
     */
    private Map<String, String> readConfigFromBitcoinConf() {
        try {
            Path bitcoinConfigDir = Paths.get(
                    System.getProperty("user.home"),
                    "AppData", "Roaming", "Bitcoin"
            );
            
            Path filePath = bitcoinConfigDir.resolve("bitcoin.conf");

            if (!Files.exists(filePath)) {
                AppLogger.info("[AppNWKConfig] bitcoin.conf not found at {}", filePath);
                return null;
            }

            List<String> lines = Files.readAllLines(filePath);
            Map<String, String> map = new HashMap<>();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                map.put(key, value);
            }

            AppLogger.info("[AppNWKConfig] Loaded {} entries from bitcoin.conf", map.size());
            return map;

        } catch (IOException e) {
            AppLogger.warn("[AppNWKConfig] Failed to read bitcoin.conf: {}", e.getMessage());
            return null;
        }
    }

    private int parseInt(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean parseBoolean(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes");
    }
}