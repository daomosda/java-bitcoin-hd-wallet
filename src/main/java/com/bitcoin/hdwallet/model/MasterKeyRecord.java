package com.bitcoin.hdwallet.model;

import java.util.Objects;

/**
 *
 * @author CONALDES
 */

public class MasterKeyRecord {
    private final String label;
    private final String network;
    private final String xprvBase58;
    private final int fingerprint;

    public MasterKeyRecord(String label, String network, String xprvBase58, int fingerprint) {
        this.label = Objects.requireNonNull(label);
        this.network = Objects.requireNonNull(network);
        this.xprvBase58 = Objects.requireNonNull(xprvBase58);
        this.fingerprint = fingerprint;
    }

    public String getLabel() {
        return label;
    }

    public String getNetwork() {
        return network;
    }

    public String getXprvBase58() {
        return xprvBase58;
    }

    public int getFingerprint() {
        return fingerprint;
    }

    public MasterKeyRecord withLabel(String newLabel) {
        return new MasterKeyRecord(newLabel, network, xprvBase58, fingerprint);
    }
}