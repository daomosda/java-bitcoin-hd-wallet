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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HwiClient {
    
    private static String className = "HwiClient";

    private final String pythonCommand;
    //private final AppLogger AppLogger;

    public HwiClient() {
        // Detect python command (python vs python3 vs py)
        this.pythonCommand = detectPythonCommand();
    }

    private String detectPythonCommand() {
        try {
            Process p = new ProcessBuilder("python", "--version").start();
            if (p.waitFor() == 0) return "python";
        } catch (IOException | InterruptedException e) { /* Ignore */ }
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            if (p.waitFor() == 0) return "python3";
        } catch (IOException | InterruptedException e) { /* Ignore */ }
        try {
            Process p = new ProcessBuilder("py", "--version").start();
            if (p.waitFor() == 0) return "py";
        } catch (IOException | InterruptedException e) { /* Ignore */ }
        
         AppLogger.error(className," Python not found in PATH. Cannot use Hardware Wallet.");
        return null;
    }

    /**
     * Calls 'hwi enumerate' to find connected devices.
     * @return 
     */
    public List<HwiDevice> enumerateDevices() {
        List<HwiDevice> devices = new ArrayList<>();
        if (pythonCommand == null) return devices;

        try {
            // Command: python -m hwi enumerate
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-m", "hwit", "enumerate");
            Process process = pb.start();
            
            String output = readOutput(process);
            int exitCode = process.waitFor();

            if (exitCode == 0 && !output.trim().isEmpty()) {
                // Simple JSON parsing via Regex (assuming clean HWI output)
                // Format: [{"model": "Trezor One", "path": "webusb:...", "fingerprint": "1234..."}, ...]
                Pattern pattern = Pattern.compile("\\{[^}]*\\}");
                Matcher matcher = pattern.matcher(output);

                while (matcher.find()) {
                    String jsonBlock = matcher.group();
                    String model = extractJsonValue(jsonBlock, "model");
                    String path = extractJsonValue(jsonBlock, "path");
                    String fingerprint = extractJsonValue(jsonBlock, "fingerprint");
                    
                    if (path != null) {
                        devices.add(new HwiDevice(model, path, fingerprint));
                    }
                }
                  AppLogger.info(className, " Found " + devices.size() + " device(s).");
            } else {
                 AppLogger.warn(className," No devices found. HWI Output: " + output);
            }

        } catch (IOException | InterruptedException e) {
             AppLogger.error(className," Error enumerating devices: " + e.getMessage());
        }
        return devices;
    }

    /**
     * Calls 'hwi signtx' to sign a PSBT.
     * @param devicePath
     * @param psbtBase64
     * @return 
     */
    public String signTransaction(String devicePath, String psbtBase64) {
        if (pythonCommand == null) return null;

        try {
            // Command: python -m hwi --device-path <path> signtx <psbt>
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonCommand);
            cmd.add("-m");
            cmd.add("hwit");
            cmd.add("--device-path");
            cmd.add(devicePath);
            cmd.add("signtx");
            cmd.add(psbtBase64);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            
            String output = readOutput(process);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // HWI returns the signed PSBT Base64 string directly
                // Note: If the user cancels on device, HWI might throw error.
                  AppLogger.info(className, " Transaction signed successfully.");
                return output.trim(); 
            } else {
                 AppLogger.error(className," Failed to sign. HWI: " + output);
            }

        } catch (IOException | InterruptedException e) {
             AppLogger.error(className," Exception during signing: " + e.getMessage());
        }
        return null;
    }

    // Helper to read stdout/stderr
    private String readOutput(Process process) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    // Helper to extract string value from simple JSON: "key":"value"
    private String extractJsonValue(String json, String key) {
        // Look for "key":"value" handling escaped quotes
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    public static class HwiDevice {
        public final String model;
        public final String path;
        public final String fingerprint;

        public HwiDevice(String model, String path, String fingerprint) {
            this.model = model;
            this.path = path;
            this.fingerprint = fingerprint;
        }

        @Override
        public String toString() {
            return model + " (" + fingerprint + ")";
        }
    }
}
