package com.bitcoin.hdwallet.chaindata;

/**
 *
 * @author DAOMOSDA
 */

import java.util.Objects;

public final class TxOutputData {

    private String txid;      // ✅ added
    private int    vout;
    private long   valueSat;
    private String scriptHex;
    private String scriptType;
    private String address;   // may be empty for non-standard scripts

    // ── Constructor ─────────────────────────────────────────────────
    public TxOutputData() {
        
    }

    public TxOutputData(String txid,
                        int vout,
                        long valueSat,
                        String scriptHex,
                        String scriptType,
                        String address) {

        if (txid == null || txid.isEmpty()) {
            throw new IllegalArgumentException("txid cannot be null/empty");
        }

        if (vout < 0) {
            throw new IllegalArgumentException("vout must be >= 0");
        }

        this.txid      = txid;
        this.vout      = vout;
        this.valueSat  = valueSat;
        this.scriptHex = scriptHex != null ? scriptHex : "";
        this.scriptType= scriptType != null ? scriptType : "";
        this.address   = address != null ? address : "";
    }

    // ── Accessors ───────────────────────────────────────────────────

    public String txid()       { return txid; }
    public int    vout()       { return vout; }
    public long   valueSat()   { return valueSat; }
    public String scriptHex()  { return scriptHex; }
    public String scriptType() { return scriptType; }
    public String address()    { return address; }
    
    public void setTxid(String txid)       { 
        this.txid = txid; 
    }
    
    public void setVout(int vout)       { 
        this.vout = vout; 
    }
    
    public void setValueSat(long valueSat)   { 
        this.valueSat = valueSat; 
    }
    
    public void setScriptHex(String scriptHex)  { 
        this.scriptHex = scriptHex; 
    }
    
    public void setScriptType(String scriptType) { 
        this.scriptType = scriptType; 
    }
    
    public void setAddress(String address)    { 
        this.address = address; 
    }

    // ── Helpers ─────────────────────────────────────────────────────
    
    /** True for OP_RETURN outputs — unspendable, no UTXO entry. */
    public boolean isOpReturn() {
        return "nulldata".equalsIgnoreCase(scriptType);
    }

    /** True for P2WPKH outputs (native segwit, bcrt1q… addresses). */
    public boolean isP2WPKH() {
        return "witness_v0_keyhash".equalsIgnoreCase(scriptType);
    }

    /** True for P2PKH outputs (legacy, m… addresses in regtest). */
    public boolean isP2PKH() {
        return "pubkeyhash".equalsIgnoreCase(scriptType);
    }

    /** True if this output has a single associated address. */
    public boolean hasAddress() {
        return address != null && !address.isBlank();
    }

    public boolean isSpendable() {
        return valueSat > 0 && !scriptType.equalsIgnoreCase("nulldata");
    }

    /** Canonical UTXO identifier */
    public String outpoint() {
        return txid + ":" + vout;
    }

    // ── Equality ────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TxOutputData other)) return false;

        return vout == other.vout &&
               Objects.equals(txid, other.txid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txid, vout);
    }

    @Override
    public String toString() {
        return "TxOutputData{" +
               "outpoint=" + outpoint() +
               ", valueSat=" + valueSat +
               ", type=" + scriptType +
               "}";
    }
}