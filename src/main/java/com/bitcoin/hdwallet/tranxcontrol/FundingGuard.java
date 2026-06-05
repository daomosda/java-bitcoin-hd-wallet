package com.bitcoin.hdwallet.tranxcontrol;

import com.bitcoin.hdwallet.cacheUtil.CachedSetObject;
import com.bitcoin.hdwallet.cacheUtil.WordFileStore;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.crypto.SegWitAddress.Network;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author CONALDES
 */

/**
 * Ensures sufficient spendable UTXOs exist to cover a transaction.
 * On regtest: mines blocks until funded.
 * On mainnet/testnet: throws with a clear message if underfunded.
 */
public final class FundingGuard {

    private final BitcoinRpcClient  walletRpc;
    private final BitcoinRpcClient nodeRpc;
    private final HdAddressManager  addressManager;
    private final Network           network;

    // Mining safety cap — prevents infinite loop on misconfiguration
    private static final int MAX_MINE_ROUNDS   = 500;
    private static final int BLOCKS_PER_ROUND  = 10;

    // Milliseconds to wait after mining before rechecking balance
    private static final long POST_MINE_WAIT_MS = 500;

    // Minimum confirmations for a UTXO to be considered spendable
    private static final int MIN_CONFIRMATIONS = 1;

    public FundingGuard(
            BitcoinRpcClient walletRpc,
            BitcoinRpcClient nodeRpc,
            HdAddressManager addressManager,
            Network          network) {
        this.walletRpc      = walletRpc;
        this.nodeRpc      = nodeRpc;
        this.addressManager = addressManager;
        this.network        = network;
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ensures spendable UTXOs cover amount + estimatedFee.
     *
     * Regtest:  mines blocks until funded.
     * Others:   throws InsufficientFundsException immediately.
     *
     * @param amount       BTC to send
     * @param estimatedFee BTC fee buffer (e.g. 0.0001)
     * @return FundingResult — balance, selected UTXOs, rounds mined
     */
    public FundingResult ensureFunded(
            BigDecimal amount,
            BigDecimal estimatedFee) throws Exception {

        BigDecimal needed = amount.add(estimatedFee);

        AppLogger.info("[FundingGuard] Checking funds:"
                + " amount={} fee={} total_needed={} BTC",
                amount, estimatedFee, needed);

        // ── Initial balance check ─────────────────────────────────────────
        FundingSnapshot snapshot = getSnapshot();

        AppLogger.info("[FundingGuard] Current balance:"
                + " spendable={} BTC utxos={}",
                snapshot.spendableBalance, snapshot.utxoCount());

        if (snapshot.spendableBalance.compareTo(needed) >= 0) {
            AppLogger.info("[FundingGuard] ✅ Sufficient funds:"
                    + " {} BTC >= {} BTC",
                    snapshot.spendableBalance, needed);
            return new FundingResult(
                    snapshot.spendableBalance,
                    snapshot.utxos,
                    0,
                    needed);
        }

        // ── Insufficient — decide strategy by network ─────────────────────
        if (network != Network.REGTEST) {
            throw new InsufficientFundsException(String.format(
                    "Insufficient funds: available=%s BTC needed=%s BTC."
                    + " Deposit funds to proceed.",
                    snapshot.spendableBalance, needed));
        }

        // ── Regtest: mine until funded ────────────────────────────────────
        return mineUntilFunded(needed, snapshot);
    }

    /**
     * Overload with default fee buffer of 0.0001 BTC.
     * @param amount
     * @return 
     * @throws java.lang.Exception
     */
    public FundingResult ensureFunded(BigDecimal amount) throws Exception {
        return ensureFunded(amount, new BigDecimal("0.0001"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // MINING LOOP (regtest only)
    // ─────────────────────────────────────────────────────────────────────

    private FundingResult mineUntilFunded(
            BigDecimal       needed,
            FundingSnapshot  initial) throws Exception {

        AppLogger.warn("[FundingGuard] Insufficient funds ({} BTC < {} BTC)."
                + " Mining blocks...",
                initial.spendableBalance, needed);

        String miningAddress = addressManager.getNextMiningAddress();
        Set<Object> cachedList = CachedSetObject.getObjectSet();
        if(!cachedList.contains(miningAddress)) {
            CachedSetObject.cacheObject(miningAddress);
        }
        AppLogger.info("[FundingGuard] Mining to: {}", miningAddress);

        FundingSnapshot current = initial;
        int             rounds  = 0;

        while (current.spendableBalance.compareTo(needed) < 0) {

            // ── Safety cap ────────────────────────────────────────────────
            if (rounds >= MAX_MINE_ROUNDS) {
                throw new IllegalStateException(String.format(
                        "[FundingGuard] Reached mining cap of %d rounds."
                        + " available=%s BTC needed=%s BTC."
                        + " Check your setup.",
                        MAX_MINE_ROUNDS,
                        current.spendableBalance,
                        needed));
            }

            rounds++;

            // ── Mine one block ────────────────────────────────────────────
            JSONArray hashes = (JSONArray) nodeRpc.executeRpc(
                    "generatetoaddress",
                    BLOCKS_PER_ROUND,
                    miningAddress);

            WordFileStore wordFileStore = (WordFileStore) CachedWalletMnemMap.getObject("wordFileStore");
            wordFileStore.appendWord(miningAddress);
            
            AppLogger.info("[FundingGuard] Round {}: mined block {}",
                    rounds,
                    hashes.getString(0).substring(0, 12) + "...");

            // ── Wait for Core to index ────────────────────────────────────
            Thread.sleep(POST_MINE_WAIT_MS);

            // ── Recheck balance ───────────────────────────────────────────
            current = getSnapshot();

            AppLogger.info("[FundingGuard] After round {}: balance={} BTC"
                    + " utxos={} needed={} BTC",
                    rounds,
                    current.spendableBalance,
                    current.utxoCount(),
                    needed);
        }

        AppLogger.info("[FundingGuard] ✅ Funded after {} round(s)."
                + " Final balance: {} BTC",
                rounds, current.spendableBalance);

        return new FundingResult(
                current.spendableBalance,
                current.utxos,
                rounds,
                needed);
    }
       
    private FundingSnapshot getSnapshot() throws Exception {
        JSONArray raw = (JSONArray) walletRpc.executeRpc(
                "listunspent",
                MIN_CONFIRMATIONS, // minconf = 1
                9_999_999,
                new JSONArray());

        List<UtxoEntry> utxos = new ArrayList<>();
        BigDecimal      total = BigDecimal.ZERO;

        for (int i = 0; i < raw.length(); i++) {
            JSONObject u        = raw.getJSONObject(i);            
            boolean spendable = u.optBoolean("spendable", false);
            boolean safe = u.optBoolean("safe", false);
            boolean coinbase = u.optBoolean("coinbase", false);
            int confs = u.optInt("confirmations", 0);

            if (!spendable || !safe) continue;
            if (coinbase && confs < 100) continue;

            BigDecimal amount = u.getBigDecimal("amount");
            total = total.add(amount);

            utxos.add(new UtxoEntry(
                    u.getString("txid"),
                    u.getInt("vout"),
                    u.getString("address"),
                    amount,
                    confs));
        }

        utxos.sort((a, b) -> b.amount.compareTo(a.amount));
        return new FundingSnapshot(total, utxos);
    }
    
    private boolean isSpendableUtxo(JSONObject u) {
        boolean spendable = u.optBoolean("spendable", false);
        boolean safe = u.optBoolean("safe", false);
        boolean coinbase = u.optBoolean("coinbase", false);
        int confs = u.optInt("confirmations", 0);

        if (!spendable || !safe) return false;
        if (coinbase && confs < 100) return false;
        return true;
    }
    
    // ─────────────────────────────────────────────────────────────────────
    // REPORT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Prints a detailed funding report — call before sendCoins() if needed.
     * @param amount
     * @param estimatedFee
     * @throws java.lang.Exception
     */
    public void printFundingReport(BigDecimal amount,
                                   BigDecimal estimatedFee) throws Exception {
        BigDecimal      needed   = amount.add(estimatedFee);
        FundingSnapshot snapshot = getSnapshot();

        System.out.println("\n── Funding Report ───────────────────────────");
        System.out.printf("  Sending       : %s BTC%n", amount);
        System.out.printf("  Estimated fee : %s BTC%n", estimatedFee);
        System.out.printf("  Total needed  : %s BTC%n", needed);
        System.out.printf("  Available     : %s BTC%n",
                snapshot.spendableBalance);
        System.out.printf("  Spendable UTXOs: %d%n", snapshot.utxoCount());
        System.out.printf("  Status        : %s%n",
                snapshot.spendableBalance.compareTo(needed) >= 0
                        ? "✅ Sufficient"
                        : "❌ Insufficient");
        System.out.println("─────────────────────────────────────────────");

        if (!snapshot.utxos.isEmpty()) {
            System.out.println("  Top UTXOs:");
            snapshot.utxos.stream().limit(5).forEach(u ->
                System.out.printf("    txid=%s:%d amount=%s BTC confs=%d%n",
                        u.txid.substring(0, 10) + "...",
                        u.vout, u.amount, u.confirmations));
        }
        System.out.println();
    }
    
    public BigDecimal estimateFee(int targetBlocks) throws Exception {
        try {
            JSONObject result = (JSONObject) nodeRpc.executeRpc(
                    "estimatesmartfee", targetBlocks);
            if (result.has("feerate")) {
                // feerate is BTC/kvbyte — typical tx ~200 vbytes
                BigDecimal feeRatePerKvb = result.getBigDecimal("feerate");
                return feeRatePerKvb
                        .multiply(BigDecimal.valueOf(200))
                        .divide(BigDecimal.valueOf(1000), 8, RoundingMode.UP);
            }
        } catch (BitcoinRpcException | JSONException e) {
            AppLogger.warn("[sendCoins] estimatesmartfee failed: {} — using"
                    + " default 0.0001 BTC", e.getMessage());
        }
        return new BigDecimal("0.0001"); // safe default
    }

    // ─────────────────────────────────────────────────────────────────────
    // DATA CLASSES
    // ─────────────────────────────────────────────────────────────────────

    /** Snapshot of current wallet UTXOs and balance. */
    private record FundingSnapshot(
            BigDecimal     spendableBalance,
            List<UtxoEntry> utxos) {
        int utxoCount() { return utxos.size(); }
    }

    /** A single spendable UTXO entry. */
    public record UtxoEntry(
            String     txid,
            int        vout,
            String     address,
            BigDecimal amount,
            int        confirmations) {}

    /** Result returned to the caller after funding is confirmed. */
    public record FundingResult(
            BigDecimal      availableBalance,
            List<UtxoEntry> spendableUtxos,
            int             roundsMined,
            BigDecimal      amountNeeded) {

        public boolean requiredMining() { return roundsMined > 0; }

        public BigDecimal surplus() {
            return availableBalance.subtract(amountNeeded);
        }

        @Override
        public String toString() {
            return String.format(
                    "FundingResult{balance=%s BTC utxos=%d mined=%d"
                    + " needed=%s surplus=%s}",
                    availableBalance, spendableUtxos.size(),
                    roundsMined, amountNeeded, surplus());
        }
    }

    /** Thrown on mainnet/testnet when funds are insufficient. */
    public static final class InsufficientFundsException
            extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }
}