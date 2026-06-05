package com.bitcoin.hdwallet.tranxcontrol;

import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.model.Utxo;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author CONALDES
 */

public final class CoinSelector {

    private final ChainIndexRepository dbIndexStore;
    private final BigDecimal      feeBuffer;
    private final BitcoinRpcClient  walletRpc;

    public CoinSelector(ChainIndexRepository dbIndexStore, BitcoinRpcClient walletRpc, BigDecimal feeBuffer) {
        this.dbIndexStore = dbIndexStore;
        this.walletRpc    = walletRpc;
        this.feeBuffer    = feeBuffer;
    }

    /**
     * Selects UTXOs to cover the target amount plus fee buffer.
     * Strategy: largest-first (minimises input count → smaller tx → lower fee)
     *
     * @param targetAmount BTC amount to send (excluding fee)
     * @return selected UTXOs whose total >= targetAmount + feeBuffer
     * @throws java.lang.Exception
     */    
    
    public List<Utxo> select(BigDecimal targetAmount) throws Exception {
        BigDecimal needed = targetAmount.add(feeBuffer);

        // ── Get mature spendable UTXOs directly from Core ─────────────────────
        // listunspent with minconf=1 returns coinbase flag correctly
        // We use Core as the source of truth — not our local DB
        List<Utxo> utxos = getMatureSpendableUtxosFromCore();

        if (utxos.isEmpty()) {
            throw new IllegalStateException(
                    "[CoinSelector] No mature spendable UTXOs available."
                    + " Mine more blocks to mature coinbase outputs.");
        }

        // Sort largest first
        utxos.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        List<Utxo>  selected = new ArrayList<>();
        BigDecimal  total    = BigDecimal.ZERO;

        for (Utxo utxo : utxos) {
            selected.add(utxo);
            total = total.add(utxo.getAmount());

            AppLogger.info("[CoinSelector] Adding UTXO: txid={}:{}"
                    + " amount={} BTC confs={} coinbase={}",
                    utxo.txid(), utxo.getVout(),
                    utxo.getAmount(), utxo.getConfirmations(),
                    utxo.coinbase());

            if (total.compareTo(needed) >= 0) break;
        }

        if (total.compareTo(needed) < 0) {
            throw new IllegalStateException(String.format(
                    "[CoinSelector] Insufficient mature funds:"
                    + " available=%s BTC needed=%s BTC.",
                    total, needed));
        }

        AppLogger.info("[CoinSelector] Selected {} UTXO(s) totalling {} BTC"
                + " to cover {} BTC",
                selected.size(), total, needed);

        return selected;
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fetches UTXOs directly from Bitcoin Core — uses Core's coinbase flag
     * which is always correct, unlike our local DB which may have coinbase=false
     * due to a mapping bug.
     *
     * Filters out:
     *  - coinbase outputs with < 100 confirmations (immature)
     *  - unconfirmed outputs
     *  - non-spendable outputs
     */
    private List<Utxo> getMatureSpendableUtxosFromCore() throws Exception {
        JSONArray raw = (JSONArray) walletRpc.executeRpc(
                "listunspent",
                1,          // minconf — confirmed only
                9_999_999,  // maxconf
                new JSONArray()); // all addresses

        List<Utxo> result = new ArrayList<>();

        for (int i = 0; i < raw.length(); i++) {
            JSONObject u        = raw.getJSONObject(i);
            boolean    coinbase = u.optBoolean("coinbase",  false);
            boolean    spendable = u.optBoolean("spendable", true);
            boolean    safe      = u.optBoolean("safe",      true);
            int        confs     = u.getInt("confirmations");

            // ✅ Skip immature coinbase — Core's coinbase flag is authoritative
            if (coinbase && confs < 100) {
                AppLogger.info("[CoinSelector] Skipping immature coinbase:"
                        + " txid={}:{} confs={}",
                        u.getString("txid").substring(0, 12) + "...",
                        u.getInt("vout"), confs);
                continue;
            }

            if (!spendable || !safe) {
                AppLogger.warn("[CoinSelector] Skipping non-spendable: {}:{}",
                        u.getString("txid").substring(0, 12) + "...",
                        u.getInt("vout"));
                continue;
            }

            BigDecimal amount = u.getBigDecimal("amount");

            result.add(new Utxo(
                    u.getString("txid"),
                    u.getInt("vout"),
                    0,           // height — not needed for selection
                    coinbase,
                    confs,
                    u.optString("address", ""),
                    u.optString("scriptPubKey", ""),
                    amount,
                    amount.multiply(BigDecimal.valueOf(100_000_000L)).longValue(),
                    spendable,
                    safe,
                    u.optString("desc", ""),
                    u.optBoolean("solvable", true),
                    false,  // spent
                    null,   // spent_by_txid
                    null    // spent_at_height
            ));
        }

        AppLogger.info("[CoinSelector] Mature spendable UTXOs from Core: {}",
                result.size());
        return result;
    }
}
