/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.chainindexing;

/**
 *
 * @author DAOMOSDA
 */

public class KeyPair {
    private final byte[] priv;
    private final byte[] pub;

    public KeyPair(byte[] priv, byte[] pub) {
        this.priv = priv;
        this.pub = pub;
    }

    public byte[] getPrivate() { return priv; }
    public byte[] getPublic() { return pub; }
}
