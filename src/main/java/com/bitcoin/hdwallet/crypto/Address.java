package com.bitcoin.hdwallet.crypto;

/**
 *
 * @author CONALDES
 */

import java.math.BigInteger;
import java.util.Arrays;

public final class Address {

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private Address() {}

    public static boolean isValid(String address, boolean testnet) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        // Quick length sanity (legacy 26–35 chars, bech32 can be longer).
        if (address.length() < 14 || address.length() > 90) {
            return false;
        }

        char first = address.charAt(0);

        // 1) Bech32 / Bech32m (segwit)
        if (address.toLowerCase().startsWith("bc1")
                || address.toLowerCase().startsWith("tb1")
                || address.toLowerCase().startsWith("bcrt1")) {
            return isValidBech32(address, testnet);
        }

        // 2) Base58 legacy (P2PKH/P2SH)
        if (BASE58_ALPHABET.indexOf(first) != -1) {
            return isValidBase58(address, testnet);
        }

        // Unknown prefix
        return false;
    }

    // -------- Base58Check validation --------

    private static boolean isValidBase58(String address, boolean testnet) {
        // decode Base58
        byte[] decoded = base58Decode(address);
        if (decoded == null || decoded.length < 4) {
            return false;
        }

        // split payload/checksum
        byte[] payload = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);

        // verify checksum
        byte[] hash = doubleSha256(payload);
        for (int i = 0; i < 4; i++) {
            if (checksum[i] != hash[i]) {
                return false;
            }
        }

        // verify version byte for network
        byte version = payload[0];

        if (!testnet) {
            // mainnet: 0x00 = P2PKH (1...), 0x05 = P2SH (3...) [web:536][web:537]
            return version == 0x00 || version == 0x05;
        } else {
            // testnet / regtest: 0x6F = P2PKH (m/n...), 0xC4 = P2SH (2...) [web:537]
            return (version & 0xFF) == 0x6F || (version & 0xFF) == 0xC4;
        }
    }

    private static byte[] base58Decode(String input) {
        BigInteger num = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) return null;
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }

        // Convert BigInteger to byte array
        byte[] bytes = num.toByteArray();

        // Strip leading sign byte if present
        if (bytes.length > 0 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }

        // Add leading zero bytes for each leading '1'
        int leadingZeros = 0;
        for (int i = 0; i < input.length() && input.charAt(i) == '1'; i++) {
            leadingZeros++;
        }

        byte[] result = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, result, leadingZeros, bytes.length);
        return result;
    }

    // You can plug in a proper SHA-256 impl; placeholder here
    private static byte[] doubleSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] first = digest.digest(data);
            return digest.digest(first);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------- Bech32 / Bech32m validation --------

    private static boolean isValidBech32(String address, boolean testnet) {
        // Use a Bech32 implementation or simple wrapper. Here we assume you have:
        // Bech32Result r = Bech32Decoder.decode(address); [web:534]
        // r.isValid(), r.getHrp(), r.getData() etc.

        address = address.toLowerCase();

        Bech32Result r = Bech32.decode(address); // you implement or import this

        if (!r.isValid()) {
            return false;
        }

        String hrp = r.getHrp();
        byte[] data = r.getData();

        // Network HRP check: mainnet vs testnet/regtest [web:506][web:534]
        if (!testnet) {
            if (!"bc".equals(hrp)) {
                return false;
            }
        } else {
            // accept both public testnet and regtest HRPs
            if (!"tb".equals(hrp) && !"bcrt".equals(hrp)) {
                return false;
            }
        }

        // segwit witness version & length checks (BIP173/BIP350) [web:506]
        int witnessVersion = data[0] & 0xFF;
        byte[] prog = convertBits(Arrays.copyOfRange(data, 1, data.length), 5, 8, false);
        if (prog == null || prog.length < 2 || prog.length > 40) {
            return false;
        }

        // v0: length must be 20 (P2WPKH) or 32 (P2WSH)
        if (witnessVersion == 0 && prog.length != 20 && prog.length != 32) {
            return false;
        }

        // v1+ additional checks possible, but this is enough for basic wallet use
        return true;
    }

    // Convert between bit groups (from BIP173)
    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        java.util.ArrayList<Byte> ret = new java.util.ArrayList<>();
        for (byte value : data) {
            int b = value & 0xff;
            if ((b >> fromBits) > 0) {
                return null;
            }
            acc = (acc << fromBits) | b;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((byte) ((acc >> bits) & maxv));
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            return null;
        }
        byte[] result = new byte[ret.size()];
        for (int i = 0; i < ret.size(); i++) result[i] = ret.get(i);
        return result;
    }

    // Dummy Bech32Result + Bech32 decoder placeholders
    // Replace with a proper Bech32 implementation, e.g. from bitcoinj or your own [web:534].

    public static final class Bech32Result {
        private final boolean valid;
        private final String hrp;
        private final byte[] data;

        public Bech32Result(boolean valid, String hrp, byte[] data) {
            this.valid = valid;
            this.hrp = hrp;
            this.data = data;
        }

        public boolean isValid() { return valid; }
        public String getHrp() { return hrp; }
        public byte[] getData() { return data; }
    }

    public static final class Bech32 {
        public static Bech32Result decode(String addr) {
            // TODO: plug in real Bech32/Bech32m decoder here (BIP173/BIP350). [web:506][web:534]
            throw new UnsupportedOperationException("Bech32.decode not implemented");
        }
    }
}