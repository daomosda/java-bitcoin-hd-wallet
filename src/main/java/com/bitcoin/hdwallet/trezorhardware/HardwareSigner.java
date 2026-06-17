/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */


import java.util.List;

/**
 * Vendor-agnostic interface for hardware wallet signing.
 *
 * Implementations (TrezorSigner, LedgerSigner, ColdcardSigner) handle
 * the device-specific USB/HID wire protocol. OwnSigner remains the
 * fallback for watch-only software signing.
 */
public interface HardwareSigner {

    /** Human-readable device name, e.g. "Trezor Model T" */
    String getDeviceName();

    /** True if a supported device is currently connected via USB */
    boolean isConnected();

    /**
     * Opens the USB/HID connection. Must be called before any other
     * operation. Throws if no device found or device locked (needs PIN).
     */
    void connect() throws HardwareWalletException;

    /** Closes the USB/HID connection. Safe to call multiple times. */
    void disconnect();

    /**
     * Fetches the master key fingerprint (first 4 bytes of HASH160
     * of the master public key) — used to verify PSBT bip32_derivs
     * belong to this device.
     */
    String getMasterFingerprint() throws HardwareWalletException;

    /**
     * Fetches the account-level xpub at a given derivation path.
     * e.g. path = "m/84'/1'/0'" → returns tpub.../zpub...
     */
    String getXpub(String derivationPath) throws HardwareWalletException;

    /**
     * Fetches a single receive/change address at a full derivation path,
     * for display-and-verify on the device screen (anti-malware check).
     */
    String getAddress(String derivationPath, boolean display)
            throws HardwareWalletException;

    /**
     * Signs a PSBT entirely on-device. The device parses the PSBT,
     * shows amounts/addresses on its screen, user confirms physically,
     * and returns the PSBT with partial signatures injected.
     *
     * @param psbtBase64 unsigned (or partially signed) PSBT
     * @return PSBT base64 with this device's signatures added
     */
    String signPsbt(String psbtBase64) throws HardwareWalletException;

    /**
     * Lists all currently connected devices of this vendor type.
     * Static-style discovery exposed as instance method for interface
     * uniformity across vendors.
     */
    List<HardwareDeviceInfo> listDevices();
}