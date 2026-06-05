package com.bitcoin.hdwallet.model;


/**
 *
 * @author CONALDES
 */


/**
 * One entry from Bitcoin Core's listunspent RPC response.
 *
 * Raw JSON shape from Core:
 * {
 *   "txid":          "b39a63d...",
 *   "vout":          0,
 *   "address":       "bcrt1q5ml...",
 *   "label":         "",
 *   "scriptPubKey":  "0014a7f6...",
 *   "amount":        50.00000000,     ← BTC, NOT satoshis
 *   "confirmations": 103,
 *   "spendable":     true,
 *   "solvable":      true,
 *   "desc":          "wpkh(...)#xxxxx",
 *   "safe":          true
 * }
 */

import java.math.BigDecimal;
import java.util.Objects;

public final class Utxo {

    // ── Fields (mutable — setters provided) ──────────────────────────
    private String     txid;
    private int        vout;
    private int        height;
    private String     address;
    private String     label;
    private String     scriptPubHex;
    private BigDecimal amount;       // BTC  e.g. 50.00000000
    private long       amountSat;    // sats e.g. 5_000_000_000
    private int        confirmations;
    private boolean    spendable;
    private boolean coinbase;
    private boolean    solvable;
    private String     descriptor;
    private boolean    safe;    
    
    private boolean spent;
    private String spentByTxid;
    private Integer spentAtHeight;

    // ── No-arg constructor (for setter-based construction) ────────────

    public Utxo() {
        this.amount    = BigDecimal.ZERO;
        this.amountSat = 0L;
        this.label     = "";
        this.address   = "";
        this.scriptPubHex = "";
        this.descriptor   = "";
    }
    
    public Utxo(
            String txid,
            int vout,
            int height,
            boolean coinbase,
            int confirmations,
            String address,
            String scriptPubHex,
            BigDecimal amount,
            long amountSat,
            boolean isSpendable,
            boolean safe,
            String descriptor,
            boolean solvable,            
            boolean spent,
            String spentByTxid,
            Integer spentAtHeight) {

        this.txid = txid;
        this.vout = vout;
        this.height = height;
        this.coinbase = coinbase;
        this.confirmations = confirmations;
        this.address = address;
        this.scriptPubHex = scriptPubHex;
        this.amount = amount;
        this.amountSat = amountSat;
        this.spendable = isSpendable;
        this.safe = safe;
        this.descriptor    = descriptor    != null ? descriptor    : "";
        this.solvable = solvable;   // ✅ 
        
        this.spent = spent;
        this.spentByTxid = spentByTxid;
        this.spentAtHeight = spentAtHeight;
    }

    // ── Full constructor ──────────────────────────────────────────────

    public Utxo(String     txid,
                int        vout,
                String     address,
                String     label,
                String     scriptPubHex,
                BigDecimal amount,
                int        confirmations,
                boolean    spendable,
                boolean coinbase,
                boolean    solvable,
                String     descriptor,
                boolean    safe) {
        this.txid          = txid;
        this.vout          = vout;
        this.address       = address       != null ? address       : "";
        this.label         = label         != null ? label         : "";
        this. scriptPubHex  =  scriptPubHex  != null ?  scriptPubHex  : "";
        this.amount        = amount        != null ? amount        : BigDecimal.ZERO;
        this.amountSat     = toSats(this.amount);
        this.confirmations = confirmations;
        this.spendable     = spendable;
        this.coinbase = coinbase;
        this.solvable      = solvable;
        this.descriptor    = descriptor    != null ? descriptor    : "";
        this.safe          = safe;
    }
    
    // ── Minimal constructor ───────────────────────────────────────────

    public Utxo(String txid, int vout, BigDecimal amount, boolean spendable, boolean coinbase) {
        this(txid, vout, "", "", "", amount, 0, spendable, coinbase, false, "", false);
    }

    // ── Factory ───────────────────────────────────────────────────────

    public static Utxo fromJson(org.json.JSONObject u) {
        return new Utxo(
            u.optString    ("txid",          ""),
            u.optInt       ("vout",          0),
            u.optString    ("address",       ""),
            u.optString    ("label",         ""),
            u.optString    ("scriptPubKey",  ""),
            u.optBigDecimal("amount",        BigDecimal.ZERO),
            u.optInt       ("confirmations", 0),
            u.optBoolean   ("spendable",     false),
            u.optBoolean   ("coinbase",     false),
            u.optBoolean   ("solvable",      false),
            u.optString    ("desc",          ""),
            u.optBoolean   ("safe",          false)
        );
    }

    // ── Getters ───────────────────────────────────────────────────────

    public String     getTxid()          { return txid;          }
    public int        getVout()          { return vout;          }
    public int        getHeight()          { return height;          }
    public String     getAddress()       { return address;       }
    public String     getLabel()         { return label;         }
    public String     getScriptPubHex()  { return scriptPubHex;  }
    public BigDecimal getAmount()        { return amount;        }  // BTC
    public long       getAmountSat()     { return amountSat;     }  // satoshis
    public int        getConfirmations() { return confirmations; }
    public boolean    isSpendable()      { return spendable;     }
    public boolean    isCoinbase()      { return coinbase;     }
    public boolean    isSolvable()       { return solvable;      }
    public String     getDescriptor()    { return descriptor;    }
    public boolean    isSafe()           { return safe;          }

    // record-style accessors (no get-prefix)
    public String     txid()             { return txid;          }
    public int        vout()             { return vout;          }
    public int        height()           { return height;         }
    public String     address()          { return address;       }
    public String     label()            { return label;         }
    public String     scriptPubHex()     { return scriptPubHex;  }
    public BigDecimal amount()           { return amount;        }
    public long       amountSat()        { return amountSat;     }
    public int        confirmations()    { return confirmations; }
    public boolean    spendable()        { return spendable;     }
    public boolean    coinbase()        { return coinbase;     }
    public boolean    solvable()         { return solvable;      }
    public String     descriptor()       { return descriptor;    }
    public boolean    safe()             { return safe;          }
    
    public boolean isSpent() { return spent; }
    public void setSpent(boolean spent) { this.spent = spent; }

    public String getSpentByTxid() { return spentByTxid; }
    public void setSpentByTxid(String spentByTxid) {
        this.spentByTxid = spentByTxid;
    }

    public Integer getSpentAtHeight() { return spentAtHeight; }
    public void setSpentAtHeight(Integer spentAtHeight) {
        this.spentAtHeight = spentAtHeight;
    }

    // ── Setters ───────────────────────────────────────────────────────

    public void setTxid(String txid) {
        this.txid = txid;
    }

    public void setVout(int vout) {
        this.vout = vout;
    }
    
    public void setHeight(int height) {
        this.height = height;
    }

    public void setAddress(String address) {
        this.address = address != null ? address : "";
    }

    public void setLabel(String label) {
        this.label = label != null ? label : "";
    }

    public void setScriptPubHex(String scriptPubHex) {
        this.scriptPubHex = scriptPubHex != null ? scriptPubHex : "";
    }

    /**
     * Sets BTC amount and recomputes satoshis automatically.
     *   setAmount(new BigDecimal("50.00000000"))
     *   → amount=50.00000000, amountSat=5_000_000_000
     * @param amount
     */
    public void setAmount(BigDecimal amount) {
        this.amount    = amount != null ? amount : BigDecimal.ZERO;
        this.amountSat = toSats(this.amount);
    }

    /**
     * Sets satoshi amount and recomputes BTC automatically.
     *   setAmountSat(5_000_000_000L)
     *   → amountSat=5_000_000_000, amount=50.00000000
     * @param amountSat
     */
    public void setAmountSat(long amountSat) {
        this.amountSat = amountSat;
        this.amount    = BigDecimal.valueOf(amountSat)
                            .divide(BigDecimal.valueOf(100_000_000L));
    }

    public void setConfirmations(int confirmations) {
        this.confirmations = confirmations;
    }

    public void setSpendable(boolean spendable) {
        this.spendable = spendable;
    }
    
    public void setCoinbase(boolean coinbase) {
        this.coinbase = coinbase;
    }
    
    public void setSolvable(boolean solvable) {
        this.solvable = solvable;
    }

    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor != null ? descriptor : "";
    }

    public void setSafe(boolean safe) {
        this.safe = safe;
    }

    // ── Derived helpers ───────────────────────────────────────────────

    public boolean isMature(int requiredConfirmations) {
        return confirmations >= requiredConfirmations;
    }

    public String outpoint() {
        return txid + ":" + vout;
    }

    // ── Equality ──────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Utxo other)) return false;
        return vout == other.vout && Objects.equals(txid, other.txid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txid, vout);
    }

    @Override
    public String toString() {
        return "Utxo{" +
               (txid != null ? txid.substring(0, Math.min(12, txid.length())) : "null") +
               ":" + vout +
               " amount=" + amount + " BTC" +
               " (" + amountSat + " sat)" +
               " confs=" + confirmations +
               (spendable ? "" : " NOT-SPENDABLE") +
               "}";
    }

    // ── Utility ───────────────────────────────────────────────────────

    private static long toSats(BigDecimal btc) {
        if (btc == null) return 0L;
        return btc.multiply(BigDecimal.valueOf(100_000_000L)).longValue();
    }
}