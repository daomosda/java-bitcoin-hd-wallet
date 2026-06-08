package com.bitcoin.hdwallet.utxosinfo;

import com.bitcoin.hdwallet.model.Utxo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 *
 * @author DAOMOSDA
 */

public class UtxoAnalyzer {

    private static final int COINBASE_MATURITY = 100;

    private static final BigDecimal DUST_THRESHOLD =
            new BigDecimal("0.00000546");

    public static UtxoSummary analyze(List<Utxo> utxos) {

        UtxoSummary s = new UtxoSummary();

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal spendable = BigDecimal.ZERO;
        BigDecimal immature = BigDecimal.ZERO;

        BigDecimal min = null;
        BigDecimal max = BigDecimal.ZERO;

        int spendableCount = 0;
        int dustCount = 0;

        for (Utxo u : utxos) {

            BigDecimal amount = u.getAmount();

            total = total.add(amount);

            // 🔴 Coinbase maturity rule
            boolean isImmature =
                    u.isCoinbase() &&
                    u.getConfirmations() < COINBASE_MATURITY;

            if (isImmature) {
                immature = immature.add(amount);
            }

            // 🔴 Spendable excludes immature
            if (u.isSpendable() && !isImmature) {
                spendable = spendable.add(amount);
                spendableCount++;
            }

            // stats
            if (min == null || amount.compareTo(min) < 0) {
                min = amount;
            }

            if (amount.compareTo(max) > 0) {
                max = amount;
            }

            if (amount.compareTo(DUST_THRESHOLD) < 0) {
                dustCount++;
            }
        }

        s.totalBalance = total;
        s.spendableBalance = spendable;
        s.immatureBalance = immature;

        s.totalUtxoCount = utxos.size();
        s.spendableCount = spendableCount;

        s.largestUtxo = max;
        s.smallestUtxo = (min == null) ? BigDecimal.ZERO : min;

        s.averageUtxo = utxos.isEmpty()
                ? BigDecimal.ZERO
                : total.divide(
                        BigDecimal.valueOf(utxos.size()),
                        8,
                        RoundingMode.HALF_UP
                );

        s.dustCount = dustCount;

        return s;
    }
}