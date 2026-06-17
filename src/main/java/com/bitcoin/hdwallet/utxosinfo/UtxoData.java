/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.utxosinfo;

/**
 *
 * @author DAOMOSDA
 */
public class UtxoData {
    public String txid;
    public int vout;
    public double btcToSats;
    public String address;
    public String scriptPubKeyHex;
    public String scriptType;
    public int height;
    public boolean coinbase;
    
    public UtxoData(String txid, int vout, double btcToSats, String address, 
            String scriptPubKeyHex, String scriptType, int height, boolean coinbase) {
        this.txid = txid;
        this.vout = vout;
        this.btcToSats = btcToSats;
        this.address = address;
        this.scriptPubKeyHex =scriptPubKeyHex;
        this.scriptType =scriptType;
        this.height = height;
        this.coinbase = coinbase;        
    }
}
