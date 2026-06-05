package com.bitcoin.hdwallet.utxosinfo;

import java.math.BigDecimal;

/**
 *
 * @author CONALDES
 */

public class UtxoSummary {

    public BigDecimal totalBalance;
    public BigDecimal spendableBalance;
    public BigDecimal immatureBalance;

    public int totalUtxoCount;
    public int spendableCount;

    public BigDecimal largestUtxo;
    public BigDecimal smallestUtxo;
    public BigDecimal averageUtxo;

    public int dustCount;

    @Override
    public String toString() {
        return """
            === UTXO Summary ===
            Total Balance      : %.8f BTC
            Spendable Balance  : %.8f BTC
            Immature Balance   : %.8f BTC

            Total UTXOs        : %d
            Spendable UTXOs    : %d

            Largest UTXO       : %.8f BTC
            Smallest UTXO      : %.8f BTC
            Average UTXO       : %.8f BTC

            Dust UTXOs         : %d
            """.formatted(
                totalBalance,
                spendableBalance,
                immatureBalance,
                totalUtxoCount,
                spendableCount,
                largestUtxo,
                smallestUtxo,
                averageUtxo,
                dustCount
            );
    }
}