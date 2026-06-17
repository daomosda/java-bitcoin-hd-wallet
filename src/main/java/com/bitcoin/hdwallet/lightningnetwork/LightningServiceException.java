/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */


public class LightningServiceException extends Exception {
    public LightningServiceException(String message) { super(message); }
    public LightningServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}