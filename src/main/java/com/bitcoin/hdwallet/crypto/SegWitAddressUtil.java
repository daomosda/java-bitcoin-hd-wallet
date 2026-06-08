package com.bitcoin.hdwallet.crypto;

import java.util.Arrays;

/**
 *
 * @author DAOMOSDA
 */

public final class SegWitAddressUtil {

    public static class WitnessProgram {
        public final int version;      // 0 for P2WPKH
        public final byte[] program;   // 20-byte hash160(pubkey)

        public WitnessProgram(int version, byte[] program) {
            this.version = version;
            this.program = program;
        }
    }

    /**
     * Decode a bech32 SegWit address into (version, program).
     * Use your existing bech32/SegWit decoder here.
     * @param address
     * @param network
     * @param testnet
     * @return 
     */
    public static WitnessProgram decodeAddress(String address,
                                           SegWitAddress.Network network) {
        // 1. Decode Bech32 / Bech32m
        Bech32.Decoded decoded = Bech32.decode(address);

        // 2. Expected HRP by network
        final String expectedHrp;
        switch (network) {
            case MAINNET:
                expectedHrp = "bc";
                break;
            case TESTNET:
                expectedHrp = "tb";
                break;
            case REGTEST:
                expectedHrp = "bcrt";
                break;
            default:
                throw new IllegalArgumentException("Unknown network: " + network);
        }

        if (!decoded.hrp.equals(expectedHrp)) {
            throw new IllegalArgumentException(
                    "Wrong HRP for network: expected " + expectedHrp + " but got " + decoded.hrp);
        }

        // 3. Extract witness version + program from 5‑bit data
        byte[] data = decoded.data;          // 5‑bit groups (no checksum)
        if (data.length < 1) {
            throw new IllegalArgumentException("Invalid SegWit address: no witness version");
        }

        int witver = data[0] & 0x1f;         // witness version (0–16)
        if (witver < 0 || witver > 16) {
            throw new IllegalArgumentException("Invalid witness version: " + witver);
        }

        byte[] witprog = Bech32.convertBits(
                Arrays.copyOfRange(data, 1, data.length), // rest is witness program
                5, 8, false
        );
        if (witprog == null) {
            throw new IllegalArgumentException("Invalid SegWit address: convertBits failed");
        }

        // 4. For your current use‑case, enforce v0 P2WPKH
        if (witver != 0) {
            throw new IllegalArgumentException("Not v0 witness address (got v" + witver + ")");
        }
        if (witprog.length != 20) {
            throw new IllegalArgumentException(
                    "Not P2WPKH witness program length=" + witprog.length);
        }

        return new WitnessProgram(witver, witprog);
    }
}
