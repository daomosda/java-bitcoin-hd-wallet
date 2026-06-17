/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

/**
 * Parses BIP32 derivation path strings into the int[] format
 * Trezor's protobuf messages expect, where hardened components
 * have bit 31 set (index | 0x80000000).
 *
 * Accepts both apostrophe and 'h' hardened notation:
 *   "m/84'/1'/0'/0/0"
 *   "m/84h/1h/0h/0/0"
 */
public final class BipPathParser {

    private static final long HARDENED_BIT = 0x80000000L;

    private BipPathParser() {}

    /**
     * @param path e.g. "m/84'/1'/0'/0/0" or "84'/1'/0'"
     * @return array of uint32 path components, hardened bit set where applicable
     */
    public static int[] parse(String path) {
        if (path == null || path.isBlank()) {
            return new int[0];
        }

        String trimmed = path.trim();

        // Strip leading "m" or "m/" — root reference, not a path component
        if (trimmed.equals("m")) {
            return new int[0];
        }
        if (trimmed.startsWith("m/")) {
            trimmed = trimmed.substring(2);
        } else if (trimmed.startsWith("M/")) {
            trimmed = trimmed.substring(2);
        }

        if (trimmed.isBlank()) {
            return new int[0];
        }

        String[] parts = trimmed.split("/");
        int[] result = new int[parts.length];

        for (int i = 0; i < parts.length; i++) {
            result[i] = parseComponent(parts[i], path);
        }

        return result;
    }

    /**
     * Parses a single path component, e.g. "84'", "84h", or "0".
     */
    private static int parseComponent(String component, String fullPathForError) {
        if (component.isBlank()) {
            throw new IllegalArgumentException(
                    "Empty path component in: " + fullPathForError);
        }

        boolean hardened = false;
        String numberPart = component;

        char last = component.charAt(component.length() - 1);
        if (last == '\'' || last == 'h' || last == 'H') {
            hardened = true;
            numberPart = component.substring(0, component.length() - 1);
        }

        long index;
        try {
            index = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid path component '" + component
                            + "' in path: " + fullPathForError, e);
        }

        if (index < 0 || index > 0x7FFFFFFFL) {
            throw new IllegalArgumentException(
                    "Path component out of range (0..2^31-1): " + component);
        }

        long value = hardened ? (index | HARDENED_BIT) : index;

        // Cast to int — bit pattern preserved, just reinterpreted as signed.
        // Protobuf uint32 fields are carried as Java int with sign reused
        // as a 32-bit bit container, matching ProtoEncoder's varint writer.
        return (int) value;
    }

    /**
     * Reverse operation — formats a path int[] back to "m/84'/1'/0'/0/0"
     * style string, used for logging.
     * @param pathInts
     * @return 
     */
    public static String format(int[] pathInts) {
        StringBuilder sb = new StringBuilder("m");
        for (int component : pathInts) {
            long unsigned = component & 0xFFFFFFFFL;
            boolean hardened = (unsigned & HARDENED_BIT) != 0;
            long index = hardened ? (unsigned & ~HARDENED_BIT) : unsigned;
            sb.append('/').append(index);
            if (hardened) sb.append('\'');
        }
        return sb.toString();
    }
}