package com.bitcoin.hdwallet.chaindata;

/**
 *
 * @author DAOMOSDA
 */


import com.bitcoin.hdwallet.crypto.HexUtils;
import java.io.ByteArrayOutputStream;
import java.util.List;

public final class TxInputData {

    private  String prevTxId;     // null for coinbase
    private int    prevVout;     // -1 for coinbase

    private String coinbaseData; // only for coinbase

    private String scriptSigHex; // empty for SegWit
    private long   sequence;

    // 🔹 NEW FIELD
    private List<String> witness; // hex stack items

    // ── Constructor ─────────────────────────────────────────────
    public TxInputData() {
        
    }

    public TxInputData(String prevTxId,
                       int prevVout,
                       String coinbaseData,
                       String scriptSigHex,
                       long sequence,
                       List<String> witness) {

        this.prevTxId     = prevTxId;
        this.prevVout     = prevVout;
        this.coinbaseData = coinbaseData;
        this.scriptSigHex = scriptSigHex != null ? scriptSigHex : "";
        this.sequence     = sequence;

        // defensive copy
        this.witness = (witness != null)
                ? List.copyOf(witness)
                : List.of();
    }

    // ── Factory for coinbase ────────────────────────────────────

    public static TxInputData coinbase(String coinbaseData) {
        return new TxInputData(
                null,
                -1,
                coinbaseData,
                "",
                0xFFFFFFFFL,
                List.of()
        );
    }

    // ── Accessors ───────────────────────────────────────────────

    public String prevTxId()     { return prevTxId; }
    public int    prevVout()     { return prevVout; }
    public String coinbaseData() { return coinbaseData; }
    public String scriptSigHex() { return scriptSigHex; }
    public long   sequence()     { return sequence; }
    
    public void setPrevTxId(String prevTxId)     { 
        this.prevTxId = prevTxId; 
    }
    
    public void  setPrevVout(int prevVout)     { 
        this.prevVout = prevVout; 
    }
    
    public void setCoinbaseData(String coinbaseData) { 
        this.coinbaseData = coinbaseData; 
    }
    
    public void setScriptSigHex(String scriptSigHex) { 
        this.scriptSigHex = scriptSigHex; 
    }
    
    public void setSequence(long   sequence)     { 
        this.sequence = sequence; 
    }

    // 🔹 NEW ACCESSOR
    public List<String> witness() { return witness; }
    
    public void setWitness(List<String> witness) { 
        this.witness = witness; 
    }

    // ── Helpers ─────────────────────────────────────────────────

    public boolean isCoinbase() {
        return coinbaseData != null;
    }
    
    public boolean hasWitness() {
        return witness != null && !witness.isEmpty();
    }

    public int witnessCount() {
        return witness.size();
    }
    
    public byte[] witnessBytes() {
        if (witness.isEmpty()) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String hex : witness) {
            byte[] b = HexUtils.hexToBytes(hex);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    // ── Debug helper ────────────────────────────────────────────

    public String shortPrevOut() {
        if (isCoinbase()) return "coinbase";
        return prevTxId.substring(0, 8) + ":" + prevVout;
    }

    // ── Object overrides ───────────────────────────────────────

    @Override
    public String toString() {
        return "TxInputData{" +
                "prev=" + shortPrevOut() +
                ", seq=" + sequence +
                ", witnessItems=" + witness.size() +
                '}';
    }
}
