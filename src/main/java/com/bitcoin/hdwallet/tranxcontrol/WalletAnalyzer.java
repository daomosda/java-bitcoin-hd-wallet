package com.bitcoin.hdwallet.tranxcontrol;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class WalletAnalyzer {

    private final Set<String> receiveAddresses = new HashSet<>();
    private final Set<String> changeAddresses = new HashSet<>();
    private final Set<String> unknownAddresses = new HashSet<>();
    
    private final BitcoinRpcClient watchRpc;
    private final String consolidationAddress;
    
    public WalletAnalyzer(BitcoinRpcClient watchRpc) {
        this.watchRpc = watchRpc;
        String consAddr = (String) CachedWalletMnemMap.getObject("consolidationAddress");
        this.consolidationAddress = consAddr.trim();
    }

    public void analyzeWallet() {
        try {
            System.out.println("Fetching data from Bitcoin Core...");

            // 1. Call listunspent
            Object rawResult = BitcoinRpcClient.executeRpc("listunspent", 0, 999999);

            // 2. The result is a JSONArray (based on your stack trace)
            if (rawResult instanceof JSONArray) {
                JSONArray utxos = (JSONArray) rawResult;

                System.out.println("Processing " + utxos.length() + " UTXOs...");

                for (int i = 0; i < utxos.length(); i++) {
                    JSONObject utxo = utxos.getJSONObject(i);
                    
                    if (utxo.has("address")) {
                        String address = utxo.getString("address");
                        classifyAddress(address);
                    }
                }
            } else {
                System.out.println("Unexpected result type or no UTXOs found.");
            }

            // 3. Print results
            printResults();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void classifyAddress(String address) throws Exception {
        // Call getaddressinfo
        Object rawInfo = BitcoinRpcClient.executeRpc("getaddressinfo", address);
        
        // The result is a JSONObject
        if (rawInfo instanceof JSONObject) {
            JSONObject info = (JSONObject) rawInfo;

            if (info.has("hdkeypath") && !info.isNull("hdkeypath")) {
                String path = info.getString("hdkeypath");
                //System.out.println("[classifyAddress()] path: " + path);
                
                // Logic: m/44'/0'/0'/CHANGE/INDEX
                String[] parts = path.split("/");
                if (parts.length >= 2) {
                    // Get the second to last part (the Change index)
                    String changeIndexStr = parts[parts.length - 2].replace("'", "");
                    //System.out.println("[classifyAddress()] changeIndexStr: " + changeIndexStr);
                    try {
                        int changeIndex = Integer.parseInt(changeIndexStr);
                        switch (changeIndex) {
                            case 0 -> receiveAddresses.add(address);
                            case 1 -> {
                                if (!consolidationAddress.equals(address)) {
                                    changeAddresses.add(address);
                                }
                            }
                            default -> unknownAddresses.add(address);
                        }
                    } catch (NumberFormatException e) {
                        unknownAddresses.add(address);
                    }
                }
            } else {
                // No HD path found (likely imported or legacy)
                unknownAddresses.add(address);
             }
        }
    } 

    private void printResults() {
        System.out.println("\n=== MINING / RECEIVING ADDRESSES (External) ===");
        receiveAddresses.forEach(System.out::println);

        System.out.println("\n=== CHANGE ADDRESSES (Internal) ===");
        changeAddresses.forEach(System.out::println);

        if (!unknownAddresses.isEmpty()) {
            System.out.println("\n=== OTHER ADDRESSES ===");
            unknownAddresses.forEach(System.out::println);
        }
    }
    
    public Set<String> getReceiveAddresses() {
        return receiveAddresses;
    }
    
    public Set<String> getChangeAddresses() {
        return changeAddresses;
    }            
}
 