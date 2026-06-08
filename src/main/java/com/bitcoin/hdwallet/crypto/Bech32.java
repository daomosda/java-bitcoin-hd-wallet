package com.bitcoin.hdwallet.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 *
 * @author DAOMOSDA
 */

// Bech32.java
public final class Bech32 {

    private Bech32() {}

    // The 32-character Bech32 charset
    private static final String  CHARSET    = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final byte[]  CHARSET_REV = new byte[128];
    public static final int BECH32_CONST  = 1;
    public static final int BECH32M_CONST = 0x2bc830a3;
    
    static {
        Arrays.fill(CHARSET_REV, (byte) -1);
        for (int i = 0; i < CHARSET.length(); i++) {
            CHARSET_REV[CHARSET.charAt(i)] = (byte) i;
        }
    }    
    
    // Generator coefficients for the BCH checksum
    private static final int[] GEN = {
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    // Human-readable parts for each network
    public static final String HRP_MAINNET = "bc";
    public static final String HRP_TESTNET = "tb";
    public static final String HRP_REGTEST = "bcrt";

    // ---------------------------------------------------------------
    // Witness version constants
    // ---------------------------------------------------------------
    public static final int WITNESS_V0 = 0;   // P2WPKH, P2WSH
    public static final int WITNESS_V1 = 1;   // P2TR (Taproot)
    
    public enum Encoding { BECH32, BECH32M }
    
    public static final class Decoded {
        public final String hrp;
        public final byte[] data;
        public final Encoding encoding;

        public Decoded(String hrp, byte[] data, Encoding encoding) {
            this.hrp = hrp;
            this.data = data;
            this.encoding = encoding;
        }
    }     
    
    private static int polymod(byte[] values) {
        int chk = 1;
        int[] generator = {
            0x3b6a57b2,
            0x26508e6d,
            0x1ea119fa,
            0x3d4233dd,
            0x2a1462b3
        };

        for (byte v : values) {
            int top = chk >>> 25;
            chk = (chk & 0x1ffffff) << 5 ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) == 1) {
                    chk ^= generator[i];
                }
            }
        }
        return chk;
    }

    /**
     * Given HRP and full data (payload + 6 checksum chars),
     * verify checksum and return which encoding it is (BECH32 vs BECH32M).
     */
    private static Encoding verifyChecksumAndGetEncoding(String hrp, byte[] data) {
        byte[] values = concatenate(hrpExpand(hrp), data);
        int pm = polymod(values);

        switch (pm) {
            case BECH32_CONST:
                return Encoding.BECH32;
            case BECH32M_CONST:
                return Encoding.BECH32M;
            default:
                throw new IllegalArgumentException("Invalid bech32 checksum");
        }
    }

    // ---------------------------------------------------------------
    // High-level Bitcoin address helpers
    // ---------------------------------------------------------------

    /**
     * Encodes a native SegWit address (P2WPKH or P2WSH).
     *
     * @param hrp            human-readable part ("bc", "tb", "bcrt")
     * @param witnessVersion 0 for P2WPKH/P2WSH, 1 for P2TR
     * @param witnessProgram 20 bytes (P2WPKH) or 32 bytes (P2WSH / P2TR)
     */
    public static String encodeSegWitAddress(String hrp,
                                              int    witnessVersion,
                                              byte[] witnessProgram) {
        if (witnessVersion < 0 || witnessVersion > 16) {
            throw new IllegalArgumentException(
                "Witness version must be 0-16, got " + witnessVersion);
        }
        if (witnessProgram.length < 2 || witnessProgram.length > 40) {
            throw new IllegalArgumentException(
                "Witness program length must be 2-40 bytes, got " + witnessProgram.length);
        }
        if (witnessVersion == 0
                && witnessProgram.length != 20
                && witnessProgram.length != 32) {
            throw new IllegalArgumentException(
                "Witness v0 program must be 20 or 32 bytes");
        }

        // Convert 8-bit witness program to 5-bit groups
        byte[] converted = convertBits(witnessProgram, 8, 5, true);

        // Prepend witness version as the first data byte
        byte[] data = new byte[1 + converted.length];
        data[0] = (byte) witnessVersion;
        System.arraycopy(converted, 0, data, 1, converted.length);

        return encode(hrp, data);
    }

    /**
     * Decodes a native SegWit address.
     *
     * @return decoded witness program bytes
     * @throws IllegalArgumentException on invalid address
     */
    public static SegWitData decodeSegWitAddress(String hrp, String address) {
        Decoded decoded = decode(address);

        if (!decoded.hrp.equals(hrp)) {
            throw new IllegalArgumentException(
                "HRP mismatch: expected '" + hrp + "', got '" + decoded.hrp + "'");
        }
        if (decoded.data.length < 1) {
            throw new IllegalArgumentException("Empty data section");
        }

        int witnessVersion = decoded.data[0];
        if (witnessVersion > 16) {
            throw new IllegalArgumentException(
                "Invalid witness version: " + witnessVersion);
        }

        byte[] programBits = Arrays.copyOfRange(decoded.data, 1, decoded.data.length);
        byte[] program     = convertBits(programBits, 5, 8, false);
        if (program == null) {
            throw new IllegalArgumentException("Invalid padding in witness program");
        }
        if (program.length < 2 || program.length > 40) {
            throw new IllegalArgumentException(
                "Invalid witness program length: " + program.length);
        }
        if (witnessVersion == 0 && program.length != 20 && program.length != 32) {
            throw new IllegalArgumentException(
                "Witness v0 program must be 20 or 32 bytes");
        }

        return new SegWitData(witnessVersion, program);
    }

    // ---------------------------------------------------------------
    // Core Bech32 encode / decode
    // ---------------------------------------------------------------

    /**
     * Encodes an HRP + data array (already in 5-bit values) into a
     * Bech32 string, appending a 6-character checksum.
     *
     * @param hrp  human-readable part (lowercase)
     * @param data array of 5-bit values (0-31 each)
     */
    public static String encode(String hrp, byte[] data) {
        hrp = hrp.toLowerCase(Locale.ROOT);
        validateHrp(hrp);

        byte[] checksum = createChecksum(hrp, data);
        byte[] combined = new byte[data.length + 6];
        System.arraycopy(data,     0, combined, 0,           data.length);
        System.arraycopy(checksum, 0, combined, data.length, 6);

        StringBuilder sb = new StringBuilder(hrp.length() + 1 + combined.length);
        sb.append(hrp).append('1');
        for (byte b : combined) {
            sb.append(CHARSET.charAt(b & 0x1f));
        }
        return sb.toString();
    }
    
    public static Decoded decode(String bech) {
        // split into hrp + data (5-bit groups including checksum)
        int pos = bech.lastIndexOf('1');
        if (pos < 1 || pos + 7 > bech.length()) {
            throw new IllegalArgumentException("Invalid bech32 string: " + bech);
        }

        String hrp = bech.substring(0, pos).toLowerCase();
        String dataPart = bech.substring(pos + 1);

        // map chars to 5-bit values (you should already have CHARSET handling here)
        byte[] data = decodeDataPart(dataPart); // your existing code

        Encoding enc = verifyChecksumAndGetEncoding(hrp, data);
        // strip off 6 checksum symbols to leave only payload
        byte[] payload = Arrays.copyOf(data, data.length - 6);

        return new Decoded(hrp, payload, enc);
    }

    private static byte[] decodeDataPart(String dataPart) {
        int len = dataPart.length();
        byte[] data = new byte[len];

        for (int i = 0; i < len; i++) {
            char c = dataPart.charAt(i);
            if (c < 0 || c >= 128) {
                throw new IllegalArgumentException("Invalid bech32 character: " + c);
            }
            byte v = CHARSET_REV[c];
            if (v == -1) {
                throw new IllegalArgumentException("Invalid bech32 character: " + c);
            }
            data[i] = v; // 0..31 (5‑bit)
        }

        return data;
    }
        
    public static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        List<Byte> result = new ArrayList<>();

        for (byte value : data) {
            int b = value & 0xFF;
            if ((b >> fromBits) > 0) {
                throw new IllegalArgumentException("Invalid data range");
            }
            acc = (acc << fromBits) | b;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                result.add((byte) ((acc >> bits) & maxv));
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else {
            if (bits >= fromBits) throw new IllegalArgumentException("Illegal zero padding");
            if (((acc << (toBits - bits)) & maxv) != 0) {
                throw new IllegalArgumentException("Non-zero padding");
            }
        }

        byte[] out = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) out[i] = result.get(i);
        return out;
    }

    // ---------------------------------------------------------------
    // Checksum internals
    // ---------------------------------------------------------------

    private static byte[] hrpExpand(String hrp) {
        int    len    = hrp.length();
        byte[] result = new byte[len * 2 + 1];
        for (int i = 0; i < len; i++) {
            result[i]         = (byte)(hrp.charAt(i) >>> 5);
            result[len + 1 + i] = (byte)(hrp.charAt(i) & 31);
        }
        result[len] = 0; // separator
        return result;
    }
    
    private static byte[] concatenate(byte[] a, byte[] b) {
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    private static boolean verifyChecksum(String hrp, byte[] data) {
        byte[] hrpExp  = hrpExpand(hrp);
        byte[] combined = new byte[hrpExp.length + data.length];
        System.arraycopy(hrpExp, 0, combined, 0,            hrpExp.length);
        System.arraycopy(data,   0, combined, hrpExp.length, data.length);
        return polymod(combined) == 1;
    }

    private static byte[] createChecksum(String hrp, byte[] data) {
        byte[] hrpExp   = hrpExpand(hrp);
        byte[] values   = new byte[hrpExp.length + data.length + 6];
        System.arraycopy(hrpExp, 0, values, 0,            hrpExp.length);
        System.arraycopy(data,   0, values, hrpExp.length, data.length);
        // values[hrpExp.length + data.length .. +6] are already zero

        int    pm       = polymod(values) ^ 1;
        byte[] checksum = new byte[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (byte)((pm >>> (5 * (5 - i))) & 31);
        }
        return checksum;
    }

    // ---------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------

    private static void validateHrp(String hrp) {
        if (hrp.isEmpty() || hrp.length() > 83) {
            throw new IllegalArgumentException(
                "HRP must be 1-83 characters, got: '" + hrp + "'");
        }
        for (char c : hrp.toCharArray()) {
            if (c < 33 || c > 126) {
                throw new IllegalArgumentException(
                    "Invalid HRP character: '" + c + "'");
            }
        }
    }

    /**
     * Returns true if {@code address} looks like a valid SegWit address
     * for the given network HRP, without throwing.
     */
    public static boolean isValidSegWitAddress(String hrp, String address) {
        try {
            decodeSegWitAddress(hrp, address);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Return types
    // ---------------------------------------------------------------

    /** Raw decoded Bech32 data (HRP + 5-bit payload, no checksum). */
    //public record Decoded(String hrp, byte[] data) {}

    /** Decoded SegWit address components. */
    public record SegWitData(int witnessVersion, byte[] program) {

        /** True if this is a P2WPKH output (v0, 20-byte program). */
        public boolean isP2WPKH() {
            return witnessVersion == 0 && program.length == 20;
        }

        /** True if this is a P2WSH output (v0, 32-byte program). */
        public boolean isP2WSH() {
            return witnessVersion == 0 && program.length == 32;
        }

        /** True if this is a P2TR (Taproot) output (v1, 32-byte program). */
        public boolean isP2TR() {
            return witnessVersion == 1 && program.length == 32;
        }
    }
}