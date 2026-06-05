package com.bitcoin.hdwallet.chaindata;

/**
 *
 * @author CONALDES
 */

import java.util.Objects;

/**
 * Immutable snapshot of a block header row as stored in our local DB.
 *
 * This is NOT the full RpcBlock (which carries all transactions).
 * StoredBlock is a lightweight read model used by:
 *   • ReorgDetector   — compare our tip hash against Core's tip hash
 *   • ChainIndexStore — return current tip, look up a block at height
 *   • ReorgAwareDatabase — identify the fork point and new tip after rollback
 *
 * All fields map 1-to-1 to columns in the `blocks` table.
 */
public final class StoredBlock {

    // ── Core identity ─────────────────────────────────────────────────

    /** Height in the canonical chain (PRIMARY KEY in `blocks`). */
    private final int    height;

    /** Block hash (hex, 64 chars). UNIQUE in `blocks`. */
    private final String hash;

    /** Hash of the preceding block. Empty string for the genesis block. */
    private final String prevHash;

    // ── Header fields ─────────────────────────────────────────────────

    /** Merkle root of all transactions in this block (hex). */
    private final String merkleRoot;

    /** Unix epoch timestamp from the block header. */
    private final long   timestamp;

    /** Compact difficulty target (e.g. "1d00ffff"). */
    private final String bits;

    /** Nonce that satisfies the proof-of-work. */
    private final long   nonce;

    /** Block version field. */
    private final int    version;

    // ── Derived / index fields ────────────────────────────────────────

    /** Number of transactions in this block. */
    private final int    txCount;

    /** Total size of the block in bytes (non-segwit serialisation). */
    private final int    sizeBytes;

    /** Block weight units (base × 4 + witness). */
    private final int    weight;

    /** Wall-clock time (Unix epoch) when we wrote this row to our DB. */
    private final long   syncedAt;

    // ── Constructors ──────────────────────────────────────────────────

    /**
     * Full constructor — used when reading a complete row from the DB.
     */
    public StoredBlock(int    height,
                       String hash,
                       String prevHash,
                       String merkleRoot,
                       long   timestamp,
                       String bits,
                       long   nonce,
                       int    version,
                       int    txCount,
                       int    sizeBytes,
                       int    weight,
                       long   syncedAt) {
        this.height    = height;
        this.hash      = requireNonBlank(hash,     "hash");
        this.prevHash  = prevHash  != null ? prevHash  : "";
        this.merkleRoot= merkleRoot != null ? merkleRoot : "";
        this.timestamp = timestamp;
        this.bits      = bits      != null ? bits      : "";
        this.nonce     = nonce;
        this.version   = version;
        this.txCount   = txCount;
        this.sizeBytes = sizeBytes;
        this.weight    = weight;
        this.syncedAt  = syncedAt;
    }

    /**
     * Minimal constructor — used by ReorgDetector / ChainIndexStore
     * when only identity fields are needed (height, hash, prevHash).
     */
    public StoredBlock(int height, String hash, String prevHash) {
        this(height, hash, prevHash,
             "", 0L, "", 0L, 0,
             0, 0, 0,
             System.currentTimeMillis() / 1000L);
    }

    /**
     * Convenience constructor that mirrors the columns returned by:
     * {@code SELECT height, hash, prev_hash, merkle_root, timestamp,
     *                bits, nonce, version, tx_count, size_bytes, weight
     *         FROM blocks WHERE …}
     */
    public static StoredBlock fromRow(int    height,
                                      String hash,
                                      String prevHash,
                                      String merkleRoot,
                                      long   timestamp,
                                      String bits,
                                      long   nonce,
                                      int    version,
                                      int    txCount,
                                      int    sizeBytes,
                                      int    weight) {
        return new StoredBlock(height, hash, prevHash,
                               merkleRoot, timestamp, bits, nonce, version,
                               txCount, sizeBytes, weight,
                               System.currentTimeMillis() / 1000L);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public int    height()     { return height;     }
    public String hash()       { return hash;       }
    public String prevHash()   { return prevHash;   }
    public String merkleRoot() { return merkleRoot; }
    public long   timestamp()  { return timestamp;  }
    public String bits()       { return bits;       }
    public long   nonce()      { return nonce;      }
    public int    version()    { return version;    }
    public int    txCount()    { return txCount;    }
    public int    sizeBytes()  { return sizeBytes;  }
    public int    weight()     { return weight;     }
    public long   syncedAt()   { return syncedAt;   }

    // ── Derived helpers ───────────────────────────────────────────────

    /**
     * True if this block has no predecessor (genesis block, height 0).
     */
    public boolean isGenesis() {
        return height == 0 || prevHash.isEmpty()
               || prevHash.equals("0000000000000000000000000000000000000000"
                                + "000000000000000000000000");
    }

    /**
     * True if this block's hash matches the given hash (case-insensitive).
     * Used by ReorgDetector when comparing Core's answer to our stored value.
     */
    public boolean hashEquals(String other) {
        return other != null && hash.equalsIgnoreCase(other.trim());
    }

    /**
     * Returns virtual size in vBytes: weight / 4, rounded up.
     * Meaningful only if {@code weight} was populated.
     */
    public int vsize() {
        return weight > 0 ? (weight + 3) / 4 : sizeBytes;
    }

    /**
     * Returns a compact "height:hash8" label useful for log lines.
     * Example: {@code 840000:0000000000}
     */
    public String label() {
        String h = hash.length() >= 10 ? hash.substring(0, 10) : hash;
        return height + ":" + h;
    }

    // ── Builder ───────────────────────────────────────────────────────

    /**
     * Fluent builder — use when constructing a StoredBlock from an
     * RpcBlock before writing it to the DB.
     *
     * <pre>{@code
     * StoredBlock sb = StoredBlock.builder()
     *     .height(block.height())
     *     .hash(block.hash())
     *     .prevHash(block.previousblockhash())
     *     .merkleRoot(block.merkleroot())
     *     .timestamp(block.time())
     *     .bits(block.bits())
     *     .nonce(block.nonce())
     *     .version(block.version())
     *     .txCount(block.tx().size())
     *     .sizeBytes(block.size())
     *     .weight(block.weight())
     *     .build();
     * }</pre>
     */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private int    height;
        private String hash;
        private String prevHash   = "";
        private String merkleRoot = "";
        private long   timestamp;
        private String bits       = "";
        private long   nonce;
        private int    version    = 1;
        private int    txCount;
        private int    sizeBytes;
        private int    weight;

        private Builder() {}

        public Builder height   (int    v) { this.height    = v; return this; }
        public Builder hash     (String v) { this.hash      = v; return this; }
        public Builder prevHash (String v) { this.prevHash  = v != null ? v : ""; return this; }
        public Builder merkleRoot(String v){ this.merkleRoot = v != null ? v : ""; return this; }
        public Builder timestamp(long   v) { this.timestamp  = v; return this; }
        public Builder bits     (String v) { this.bits       = v != null ? v : ""; return this; }
        public Builder nonce    (long   v) { this.nonce      = v; return this; }
        public Builder version  (int    v) { this.version    = v; return this; }
        public Builder txCount  (int    v) { this.txCount    = v; return this; }
        public Builder sizeBytes(int    v) { this.sizeBytes  = v; return this; }
        public Builder weight   (int    v) { this.weight     = v; return this; }

        public StoredBlock build() {
            return new StoredBlock(height, hash, prevHash,
                                   merkleRoot, timestamp, bits, nonce, version,
                                   txCount, sizeBytes, weight,
                                   System.currentTimeMillis() / 1000L);
        }
    }

    // ── Equality / hashing ────────────────────────────────────────────

    /**
     * Two StoredBlocks are equal when their height AND hash agree.
     * Height alone is insufficient (two competing blocks at the same
     * height during a reorg must be considered different).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StoredBlock other)) return false;
        return height == other.height
               && hash.equalsIgnoreCase(other.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(height, hash.toLowerCase());
    }

    // ── toString ─────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "StoredBlock{"
             + "height="    + height
             + ", hash="    + hash
             + ", prevHash="+ prevHash
             + ", txCount=" + txCount
             + ", size="    + sizeBytes
             + ", ts="      + timestamp
             + '}';
    }

    // ── Internal validation ───────────────────────────────────────────

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "StoredBlock." + field + " must not be null or blank");
        }
        return value;
    }
}