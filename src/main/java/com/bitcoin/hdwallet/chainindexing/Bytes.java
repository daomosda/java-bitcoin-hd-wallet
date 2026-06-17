/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.chainindexing;


/**
 *
 * @author DAOMOSDA
 */

public final class Bytes {
    private Bytes() {}

    public static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) {
            if (a != null) len += a.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) {
            if (a == null) continue;
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}

