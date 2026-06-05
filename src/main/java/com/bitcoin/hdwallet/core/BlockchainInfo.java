package com.bitcoin.hdwallet.core;

/**
 *
 * @author CONALDES
 */

// BlockchainInfo.java — typed wrapper for every field

import org.json.JSONObject;

/**
 * Typed representation of Bitcoin Core's getblockchaininfo response.
 *
 * Every field that Core returns is captured here so callers
 * can use strongly-typed accessors instead of raw JSON.
 */
public final class BlockchainInfo {

    // ── Fields ────────────────────────────────────────────────────────

    /**
     * Network name.
     * Possible values: "main", "test", "signet", "regtest"
     * Your node returns: "regtest"
     */
    private final String  chain;

    /**
     * Number of validated blocks in the best chain.
     * After mining 105 blocks: 105
     */
    private final int     blocks;

    /**
     * Number of validated block headers (may lead `blocks` during IBD).
     * In regtest, always equals `blocks`.
     */
    private final int     headers;

    /**
     * Hash of the currently best known block.
     * 64-character hex string.
     */
    private final String  bestBlockHash;

    /**
     * Proof-of-work difficulty of the current chain tip.
     * In regtest this is always near zero (~4.6e-10) because
     * the difficulty target is set to the minimum.
     */
    private final double  difficulty;

    /**
     * Unix epoch timestamp of the current best block header.
     * e.g. 1745318255
     */
    private final long    time;

    /**
     * Median time of the last 11 blocks (BIP 113).
     * Used for locktime / CSV calculations.
     */
    private final long    medianTime;

    /**
     * Estimate of how much of the chain has been verified (0.0 – 1.0).
     * In regtest: always 1.0 (fully synced).
     */
    private final double  verificationProgress;

    /**
     * True if the node is still in Initial Block Download mode.
     * In regtest with any blocks mined: false.
     */
    private final boolean initialBlockDownload;

    /**
     * Hex string representing total cumulative PoW on this chain.
     * e.g. "00000000000000000000000000000000000000000000000000000000000000d4"
     */
    private final String  chainWork;

    /**
     * Total size of all block and undo files on disk, in bytes.
     * e.g. 48291 for a small regtest chain.
     */
    private final long    sizeOnDisk;

    /**
     * True if the node is running in pruned mode (old blocks deleted).
     * In regtest: typically false.
     */
    private final boolean pruned;

    /**
     * If pruned=true: lowest block height still on disk.
     * -1 when not pruned.
     */
    private final int     pruneHeight;

    /**
     * Any network or consensus warnings from Core.
     * Empty string "" when all is well.
     */
    private final String  warnings;

    // ── Constructor ───────────────────────────────────────────────────

    public BlockchainInfo(String  chain,
                          int     blocks,
                          int     headers,
                          String  bestBlockHash,
                          double  difficulty,
                          long    time,
                          long    medianTime,
                          double  verificationProgress,
                          boolean initialBlockDownload,
                          String  chainWork,
                          long    sizeOnDisk,
                          boolean pruned,
                          int     pruneHeight,
                          String  warnings) {
        this.chain                = chain;
        this.blocks               = blocks;
        this.headers              = headers;
        this.bestBlockHash        = bestBlockHash;
        this.difficulty           = difficulty;
        this.time                 = time;
        this.medianTime           = medianTime;
        this.verificationProgress = verificationProgress;
        this.initialBlockDownload = initialBlockDownload;
        this.chainWork            = chainWork;
        this.sizeOnDisk           = sizeOnDisk;
        this.pruned               = pruned;
        this.pruneHeight          = pruneHeight;
        this.warnings             = warnings;
    }

    // ── Factory ───────────────────────────────────────────────────────

    /**
     * Parses a raw getblockchaininfo result JSONObject.
     *
     * @param r  the "result" object from the RPC envelope
     */
    public static BlockchainInfo fromJson(JSONObject r) {
        return new BlockchainInfo(
            r.optString ("chain",                "unknown"),
            r.optInt    ("blocks",               0),
            r.optInt    ("headers",              0),
            r.optString ("bestblockhash",        ""),
            r.optDouble ("difficulty",           0.0),
            r.optLong   ("time",                 0L),
            r.optLong   ("mediantime",           0L),
            r.optDouble ("verificationprogress", 0.0),
            r.optBoolean("initialblockdownload", false),
            r.optString ("chainwork",            ""),
            r.optLong   ("size_on_disk",         0L),
            r.optBoolean("pruned",               false),
            r.optInt    ("pruneheight",          -1),
            r.optString ("warnings",             "")
        );
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String  chain()                { return chain;                }
    public int     blocks()               { return blocks;               }
    public int     headers()              { return headers;              }
    public String  bestBlockHash()        { return bestBlockHash;        }
    public double  difficulty()           { return difficulty;           }
    public long    time()                 { return time;                 }
    public long    medianTime()           { return medianTime;           }
    public double  verificationProgress() { return verificationProgress; }
    public boolean initialBlockDownload() { return initialBlockDownload; }
    public String  chainWork()            { return chainWork;            }
    public long    sizeOnDisk()           { return sizeOnDisk;           }
    public boolean pruned()               { return pruned;               }
    public int     pruneHeight()          { return pruneHeight;          }
    public String  warnings()             { return warnings;             }

    // ── Derived helpers ───────────────────────────────────────────────

    /** True when this node is on the regtest network. */
    public boolean isRegtest()  { return "regtest".equals(chain);  }

    /** True when this node is on the testnet network. */
    public boolean isTestnet()  { return "test".equals(chain);     }

    /** True when this node is on mainnet. */
    public boolean isMainnet()  { return "main".equals(chain);     }

    /** True when headers and blocks are equal (fully synced). */
    public boolean isFullySynced() { return headers == blocks; }

    /** True when Core reports any warning text. */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isBlank();
    }

    /**
     * Short best-block-hash for log lines (first 12 chars).
     * e.g. "4a2b3c1d5e6f"
     */
    public String shortHash() {
        return bestBlockHash.length() >= 12
            ? bestBlockHash.substring(0, 12)
            : bestBlockHash;
    }

    // ── toString ─────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "BlockchainInfo{" +
               "chain="       + chain          +
               " blocks="     + blocks         +
               " headers="    + headers        +
               " tip="        + shortHash()    +
               " ibd="        + initialBlockDownload +
               " pruned="     + pruned         +
               " warnings='"  + warnings + "'" +
               "}";
    }
}