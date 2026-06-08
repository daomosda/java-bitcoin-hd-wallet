package com.bitcoin.hdwallet.tranxcontrol;

/**
 *
 * @author DAOMOSDA
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class PsbtUtils {

    /**
     * Parse a path like "m/84h/1h/0h/0/0" into uint32 indices with hardened bit.
     */
    public static int[] parsePath(String path) {
        if (!path.startsWith("m/")) {
            throw new IllegalArgumentException("Path must start with m/: " + path);
        }
        String[] parts = path.substring(2).split("/");
        List<Integer> result = new ArrayList<>(parts.length);

        for (String p : parts) {
            boolean hardened = p.endsWith("'") || p.endsWith("h");
            String numPart = hardened ? p.substring(0, p.length() - 1) : p;
            long index = Long.parseLong(numPart);
            if (index < 0 || index > 0x7fffffffL) {
                throw new IllegalArgumentException("Invalid index in path: " + p);
            }
            int val = (int) index;
            if (hardened) {
                val |= 0x80000000;
            }
            result.add(val);
        }

        int[] arr = new int[result.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = result.get(i);
        }
        return arr;
    }

    /**
     * Build the PSBT BIP32 derivation value:
     *   4-byte fingerprint + 4-byte LE child index * N
     */
    public static byte[] buildBip32DerivValue(byte[] fingerprint4, String path) {
        if (fingerprint4.length != 4) {
            throw new IllegalArgumentException("Fingerprint must be 4 bytes");
        }
        int[] indices = parsePath(path);

        ByteBuffer buf = ByteBuffer
                .allocate(4 + 4 * indices.length)
                .order(ByteOrder.LITTLE_ENDIAN);

        buf.put(fingerprint4);
        for (int idx : indices) {
            buf.putInt(idx); // little-endian because of the order we set
        }
        return buf.array();
    }
}