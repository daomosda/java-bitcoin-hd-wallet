package com.bitcoin.hdwallet.lightningnetwork;

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.UtxoSyncManager;

/**
 *
 * @author DAOMOSDA
 */

public class PaymentOrchestrator {

    private final MerchantPaymentHandler merchantHandler;
    private final CustomerPaymentHandler customerHandler;
    private final UtxoSyncManager utxoSync;

    public PaymentOrchestrator(MerchantPaymentHandler merchantHandler,
                               CustomerPaymentHandler customerHandler,
                               UtxoSyncManager utxoSync) {
        this.merchantHandler = merchantHandler;
        this.customerHandler = customerHandler;
        this.utxoSync = utxoSync;
    }

    // Merchant flow (Brade)
    public void runMerchantFlow() {
        new Thread(() -> {
            try {
                int chainHeight = getChainHeight();
                merchantHandler.receivePaymentForGoods(chainHeight);
            } catch (PaymentException e) {
                AppLogger.error("Merchant flow failed", e);
            }
        }, "merchant-flow").start();
    }

    // Customer flow (Coda)
    public void runCustomerFlow(String channelId, byte[] pubKeyBytes) {
        new Thread(() -> {
            try {
                int chainHeight = getChainHeight();
                customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);
            } catch (PaymentException e) {
                AppLogger.error("Customer flow failed", e);
            }
        }, "customer-flow").start();
    }

    private int getChainHeight() {
        try {
            UtxoSyncManager.SyncResult res = utxoSync.runOnce();
            return (int) res.getBlockHeight();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get chain height", e);
        }
    }
}