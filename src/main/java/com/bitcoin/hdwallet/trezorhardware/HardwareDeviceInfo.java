/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

/** Identifies a connected USB HID hardware wallet before opening it. */
public record HardwareDeviceInfo(
        String vendor,        // "Trezor", "Ledger", "Coldcard"
        String model,         // "Model T", "Model One"
        String serialNumber,
        int    vendorId,      // USB VID
        int    productId,     // USB PID
        String path) {        // hid4java device path — used to open()
}