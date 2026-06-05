package com.bitcoin.hdwallet.chaindata;

/**
 *
 * @author CONALDES
 */

// ── BlockData.java ───────────────────────────────────────────────────
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * All data for one block as returned by getblock(verbosity=2).
 *
 * Maps from getblock response:
 *
 * {
 *   "hash":              "00000088...",
 *   "height":            203,
 *   "previousblockhash": "0000007f...",
 *   "merkleroot":        "b39a63d...",
 *   "time":              1715000203,
 *   "bits":              "207fffff",
 *   "nonce":             0,
 *   "version":           536870912,
 *   "size":              181,
 *   "weight":            724,
 *   "tx":                [ TxData... ]
 * }
 */
public final class BlockData {

    private final String        hash;
    private final int           height;
    private final String        prevHash;      // empty string for genesis
    private final String        merkleRoot;
    private final long          timestamp;
    private final String        bits;
    private final long          nonce;
    private final int           version;
    private final int           size;
    private final int           weight;
    private final List<TxData>  transactions;

    // ── Constructor ───────────────────────────────────────────────────

    public BlockData(String        hash,
                     int           height,
                     String        prevHash,
                     String        merkleRoot,
                     long          timestamp,
                     String        bits,
                     long          nonce,
                     int           version,
                     int           size,
                     int           weight,
                     List<TxData>  transactions) {
        this.hash         = Objects.requireNonNull(hash, "hash");
        this.height       = height;
        this.prevHash     = prevHash   != null ? prevHash   : "";
        this.merkleRoot   = merkleRoot != null ? merkleRoot : "";
        this.timestamp    = timestamp;
        this.bits         = bits       != null ? bits       : "";
        this.nonce        = nonce;
        this.version      = version;
        this.size         = size;
        this.weight       = weight;
        this.transactions = transactions != null
            ? Collections.unmodifiableList(transactions)
            : Collections.emptyList();
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String       hash()         { return hash;         }
    public int          height()       { return height;       }
    public String       prevHash()     { return prevHash;     }
    public String       merkleRoot()   { return merkleRoot;   }
    public long         timestamp()    { return timestamp;    }
    public String       bits()         { return bits;         }
    public long         nonce()        { return nonce;        }
    public int          version()      { return version;      }
    public int          size()         { return size;         }
    public int          weight()       { return weight;       }
    public List<TxData> transactions() { return transactions; }

    // ── Derived helpers ───────────────────────────────────────────────

    public int     txCount()   { return transactions.size();       }
    public boolean isGenesis() { return height == 0 || prevHash.isEmpty(); }

    /** Virtual size in vBytes: weight/4 rounded up. */
    public int vsize() {
        return weight > 0 ? (weight + 3) / 4 : size;
    }

    /** Short hash for log lines — first 12 chars. */
    public String shortHash() {
        return hash.length() >= 12 ? hash.substring(0, 12) : hash;
    }

    /** "height:shortHash" label for log lines. */
    public String label() {
        return height + ":" + shortHash();
    }

    // ── Equality ──────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockData other)) return false;
        return height == other.height && hash.equalsIgnoreCase(other.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(height, hash.toLowerCase());
    }

    @Override
    public String toString() {
        return "BlockData{" + label()
             + " txs="   + transactions.size()
             + " size="  + size
             + " weight=" + weight
             + "}";
    }
}