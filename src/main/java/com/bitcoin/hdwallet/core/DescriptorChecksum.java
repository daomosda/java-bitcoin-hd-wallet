package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import java.nio.charset.StandardCharsets;

public class DescriptorChecksum {

    private static final String CHARSET = "0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"";

    public static String addChecksum(String descriptor) {
        long c = 0;
        byte[] descBytes = descriptor.getBytes(StandardCharsets.US_ASCII);

        int[] cls = new int[descBytes.length];
        for (int i = 0; i < descBytes.length; i++) {
            int charIndex = CHARSET.indexOf(descBytes[i]);
            if (charIndex == -1) throw new IllegalArgumentException("Invalid character in descriptor: " + descBytes[i]);
            cls[i] = charIndex;
        }

        for (int e : cls) {
            long c2 = (c >> 35) ^ e;
            c = ((c & 0x07FFFFFFFFL) << 5) ^ c2;
            c = ((c >> 35) ^ (c2 & 0x1F)) * 9 + c;
        }

        String checksum = "";
        for (int i = 0; i < 8; i++) {
            int charIndex = (int) ((c >> (5 * (7 - i))) & 0x1F);
            checksum += CHARSET.charAt(charIndex);
        }

        return descriptor + "#" + checksum;
    }
}