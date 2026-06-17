/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author DAOMOSDA
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ScriptWriter {
    private ScriptWriter() {}

    public static void writePush(ByteArrayOutputStream out, byte[] data) {
        int len = data.length;
        try {
            if (len <= 75) {
                out.write(len);
            } else if (len <= 0xFF) {
                out.write(0x4c);        // OP_PUSHDATA1
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(0x4d);        // OP_PUSHDATA2
                out.write(len & 0xFF);
                out.write((len >>> 8) & 0xFF);
            } else {
                throw new IllegalArgumentException("Pushdata too large");
            }
            out.write(data);
        } catch (IOException e) {
            // ByteArrayOutputStream doesn't throw in practice
            throw new RuntimeException(e);
        }
    }
}
