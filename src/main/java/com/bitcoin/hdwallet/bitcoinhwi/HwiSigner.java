/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.bitcoinhwi;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.AppLogger;
import java.util.List;

public class HwiSigner { // Implements your Signer interface if you have one
    
    private static String className = "HwiSigner";

    private final HwiClient hwiClient;
    private final String walletFingerprint; // e.g., "98667b10" from your logs

    public HwiSigner(String walletFingerprint) {
        this.walletFingerprint = walletFingerprint;
        this.hwiClient = new HwiClient();
    }

    /**
     * Main entry point to sign a transaction using the hardware wallet.
     * Matches the flow seen in logs: [OwnSigner] Signing X input(s)...
     * @param psbtBase64
     * @return 
     */
    public String signPsbt(String psbtBase64) {
        AppLogger.info(className, " Looking for Hardware Wallet...");

        // 1. Enumerate Devices
        List<HwiClient.HwiDevice> devices = hwiClient.enumerateDevices();

        if (devices.isEmpty()) {
            AppLogger.warn(className, " No hardware devices found. Please connect a device.");
            return null;
        }

        // 2. Select Device (Match Fingerprint)
        HwiClient.HwiDevice selectedDevice = null;
        
        for (HwiClient.HwiDevice dev : devices) {
            // Check if this device matches our wallet's fingerprint
            if (walletFingerprint.equals(dev.fingerprint)) {
                selectedDevice = dev;
                break;
            }
        }

        // If no fingerprint match, we might need to ask the user or pick the first one
        // For this example, we default to the first device if no match is found (or if fingerprint is unknown)
        if (selectedDevice == null) {
            AppLogger.warn(className, " No device matching wallet fingerprint found. Using first available: " + devices.get(0).model);
            selectedDevice = devices.get(0);
        } else {
            AppLogger.info(className, " Found matching device: " + selectedDevice.model);
        }

        // 3. Sign
        AppLogger.info(className, " Please confirm the transaction on your " + selectedDevice.model + " device.");
        String signedPsbt = hwiClient.signTransaction(selectedDevice.path, psbtBase64);

        return signedPsbt;
    }
}
