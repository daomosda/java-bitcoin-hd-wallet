package com.bitcoin.hdwallet.lightningnetwork;


import com.bitcoin.hdwallet.networkmanager.NetworkManager;
import com.bitcoin.hdwallet.model.InvoicePayload;
import com.bitcoin.hdwallet.model.Message;
import com.bitcoin.hdwallet.model.HTLC;
import com.bitcoin.hdwallet.model.Invoice;
import com.bitcoin.hdwallet.core.AppLogger;
import java.util.Map;

/**
 *
 * @author CONALDES
 */

// ─── Merchant side ───────────────────────────────────────────────────────────

public class MerchantPaymentHandler {

    private NetworkManager   networkManager;
    private LightningService lightningService;
    
    public MerchantPaymentHandler() {
    }

    public MerchantPaymentHandler(NetworkManager networkManager,
                                  LightningService lightningService) {
        this.networkManager   = networkManager;
        this.lightningService = lightningService;
    }
    
    public void setNetworkManager(NetworkManager networkManager) {
        this.networkManager = networkManager;
    }
    
    public void setLightningService(LightningService lightningService) {
        this.lightningService = lightningService;
    }

    public void receivePaymentForGoods(int chainHeight) throws PaymentException {
        AppLogger.section("Brade — receivePaymentForGoods");
        String channelId = null;
        try {
            // 1. Wait for Coda to introduce herself over P2P.
            //    Node B must have connected to Node A's P2P port (8333) at startup
            //    before this flow runs — that is a one-time bootstrap concern, not
            //    something that belongs inside every payment round.
            String codaPubKeyStr = networkManager.waitForLnPubKey();

            // 2. Resolve Coda's peer ID from her pubkey so we can address her
            //    directly for invoice delivery and channel ID notification.
            //    Declared here so it is available for the rest of the method.
            String codaPeerId = networkManager.getPeerByPubKey(codaPubKeyStr);

            // 3. Open channel to Coda (Node A → Node B on P2P port 8334).
            //    Brade funds the full capacity on-chain; Coda starts at zero.
            channelId = lightningService.openChannel(
                codaPubKeyStr,
                LightningPaymentConstants.CHANNEL_CAPACITY_SAT
            );
            //channelId = "3a2b1c4d5e6f7890abcdef1234567890abcdef1234567890abcdef1234567890:0"
                        
            // After opening a channel, set the channel ID
            ((LightningServiceImpl) lightningService).setLastOpenedChannelId(channelId);
            
            // Get last opened channel ID
            String lastChannelId = lightningService.getLastOpenedChannelId();
            if (lastChannelId != null) {
                System.out.println("Last channel: " + lastChannelId);
            }

            // 4. Notify Coda of the channel ID over P2P so her node can store it
            //    and CodaUI can read it before calling POST /api/customer/pay.
            //    This replaces the manual copy-paste step.
            networkManager.findPeer(codaPeerId)
                .orElseThrow(() -> new PaymentException(
                    "Customer peer not reachable for channel ID delivery: " + codaPeerId))
                .sendMessage(new Message(Message.Type.CHANNEL_ID, channelId,
                    networkManager.getLocalId()));

            // 5. Push inbound liquidity to Coda so she has a balance to pay with.
            //    PUSH_AMOUNT_SAT — not MSAT — pushFunds operates in satoshis.
            lightningService.pushFunds(
                channelId,
                LightningPaymentConstants.PUSH_AMOUNT_MSAT
            );

            // 6. Sign the opening commitment and wait for Coda's revoke_and_ack.
            //    Node B must call channel.notifyAll() in its REVOCATION_ACK
            //    message handler, otherwise this blocks until timeout.
            lightningService.signAndSend(channelId);

            // 7. Create invoice and persist the preimage before sharing it.
            Map.Entry<Invoice, byte[]> invoiceEntry =
                lightningService.createInvoice(
                    LightningPaymentConstants.INVOICE_AMOUNT_SAT,
                    codaPubKeyStr
                );
            Invoice invoice  = invoiceEntry.getKey();
            byte[]  preimage = invoiceEntry.getValue();
            lightningService.storePreimage(invoice.getPaymentHash(), preimage);

            // 8. Deliver invoice to Coda over P2P (Node A:8333 → Node B:8334).
            InvoicePayload payload = InvoicePayload.fromInvoice(invoice, channelId);
            networkManager.findPeer(codaPeerId)
                .orElseThrow(() -> new PaymentException(
                    "Customer peer not reachable for invoice delivery: " + codaPeerId))
                .sendMessage(Message.invoice(payload, networkManager.getLocalId()));

            // 9. Wait for the HTLC to arrive from Coda (sent from Node B:8334).
            HTLC htlc = lightningService.waitForIncomingHTLC(
                channelId,
                invoice.getPaymentHash()
            );

            // 10. Validate HTLC amount and CLTV expiry before revealing preimage.
            validateIncomingHtlc(htlc, invoice, chainHeight);

            // 11. Fulfill the HTLC — Brade's balance increases by invoice amount.
            lightningService.fulfillHTLC(
                channelId,
                invoice.getPaymentHash(),
                preimage
            );

            // 12. Final commitment round — send fulfilled state to Coda,
            //     wait for her revoke_and_ack over P2P (Node B:8334 → Node A:8333).
            lightningService.signAndSend(channelId);

            AppLogger.info("receivePaymentForGoods", "Payment settled successfully");
            lightningService.printChannelBalance(channelId);

        } catch (PaymentException e) {
            attemptHtlcCleanup(channelId, e.getStage());
            throw e;
        } catch (LightningServiceException | InterruptedException e) {
            attemptHtlcCleanup(channelId, PaymentException.PaymentStage.CLEANUP);
            throw PaymentException.wrap(PaymentException.PaymentStage.CLEANUP, channelId, e);
        }
    }
           
    public void receivePaymentAPIs(double capacitySat, int chainHeight) throws PaymentException {
        AppLogger.section("Brade — receivePaymentForGoods");
        String channelId = null;
        try {
            // 1. Wait for Coda to introduce herself over P2P.
            //    Node B must have connected to Node A's P2P port (8333) at startup
            //    before this flow runs — that is a one-time bootstrap concern, not
            //    something that belongs inside every payment round.
            String codaPubKeyStr = networkManager.waitForLnPubKey();

            // 2. Resolve Coda's peer ID from her pubkey so we can address her
            //    directly for invoice delivery and channel ID notification.
            //    Declared here so it is available for the rest of the method.
            String codaPeerId = networkManager.getPeerByPubKey(codaPubKeyStr);

            // 3. Open channel to Coda (Node A → Node B on P2P port 8334).
            //    Brade funds the full capacity on-chain; Coda starts at zero.
            channelId = lightningService.openChannel(codaPubKeyStr, (long) capacitySat);

            // 4. Notify Coda of the channel ID over P2P so her node can store it
            //    and CodaUI can read it before calling POST /api/customer/pay.
            //    This replaces the manual copy-paste step.
            networkManager.findPeer(codaPeerId)
                .orElseThrow(() -> new PaymentException(
                    "Customer peer not reachable for channel ID delivery: " + codaPeerId))
                .sendMessage(new Message(Message.Type.CHANNEL_ID, channelId,
                    networkManager.getLocalId()));

            // 5. Push inbound liquidity to Coda so she has a balance to pay with.
            //    PUSH_AMOUNT_SAT — not MSAT — pushFunds operates in satoshis.
            lightningService.pushFunds(
                channelId,
                LightningPaymentConstants.PUSH_AMOUNT_MSAT
            );

            // 6. Sign the opening commitment and wait for Coda's revoke_and_ack.
            //    Node B must call channel.notifyAll() in its REVOCATION_ACK
            //    message handler, otherwise this blocks until timeout.
            lightningService.signAndSend(channelId);

            // 7. Create invoice and persist the preimage before sharing it.
            Map.Entry<Invoice, byte[]> invoiceEntry =
                lightningService.createInvoice(
                    LightningPaymentConstants.INVOICE_AMOUNT_SAT,
                    codaPubKeyStr
                );
            Invoice invoice  = invoiceEntry.getKey();
            byte[]  preimage = invoiceEntry.getValue();
            lightningService.storePreimage(invoice.getPaymentHash(), preimage);

            // 8. Deliver invoice to Coda over P2P (Node A:8333 → Node B:8334).
            InvoicePayload payload = InvoicePayload.fromInvoice(invoice, channelId);
            networkManager.findPeer(codaPeerId)
                .orElseThrow(() -> new PaymentException(
                    "Customer peer not reachable for invoice delivery: " + codaPeerId))
                .sendMessage(Message.invoice(payload, networkManager.getLocalId()));

            // 9. Wait for the HTLC to arrive from Coda (sent from Node B:8334).
            HTLC htlc = lightningService.waitForIncomingHTLC(
                channelId,
                invoice.getPaymentHash()
            );

            // 10. Validate HTLC amount and CLTV expiry before revealing preimage.
            validateIncomingHtlc(htlc, invoice, chainHeight);

            // 11. Fulfill the HTLC — Brade's balance increases by invoice amount.
            lightningService.fulfillHTLC(
                channelId,
                invoice.getPaymentHash(),
                preimage
            );

            // 12. Final commitment round — send fulfilled state to Coda,
            //     wait for her revoke_and_ack over P2P (Node B:8334 → Node A:8333).
            lightningService.signAndSend(channelId);

            AppLogger.info("receivePaymentForGoods", "Payment settled successfully");
            lightningService.printChannelBalance(channelId);

        } catch (PaymentException e) {
            attemptHtlcCleanup(channelId, e.getStage());
            throw e;
        } catch (LightningServiceException | InterruptedException e) {
            attemptHtlcCleanup(channelId, PaymentException.PaymentStage.CLEANUP);
            throw PaymentException.wrap(PaymentException.PaymentStage.CLEANUP, channelId, e);
        }
    }
        
    // Updated cleanup helper — logs the stage that triggered it:
    private void attemptHtlcCleanup(String channelId, PaymentException.PaymentStage failedAt) {
        if (channelId == null) return;
        try {
            AppLogger.warn("cleanup", "Cleaning up after failure at stage: " + failedAt);
            lightningService.failPendingHtlcs(channelId);
        } catch (LightningServiceException cleanup) {
            AppLogger.warn("cleanup",
                "Channel cleanup failed: " + cleanup.getMessage());
        }
    }

    private void validateIncomingHtlc(HTLC htlc, Invoice invoice, int chainHeight)
            throws PaymentException {
        long requiredMsat = invoice.getAmountSat() * 1_000L;
        if (htlc.getAmountMsat() < requiredMsat) {
            throw new PaymentException(String.format(
                "HTLC amount too low: expected >= %d msat, got %d",
                requiredMsat, htlc.getAmountMsat()
            ));
        }

        long minExpiry = chainHeight + LightningPaymentConstants.CLTV_MIN_EXPIRY_DELTA;
        if (htlc.getCltvExpiry() < minExpiry) {
            throw new PaymentException(String.format(
                "HTLC expiry too soon: expected >= %d, got %d",
                minExpiry, htlc.getCltvExpiry()
            ));
        }
    }

    private void attemptHtlcCleanup(String channelId) {
        if (channelId == null) return;
        try {
            lightningService.failPendingHtlcs(channelId);
        } catch (LightningServiceException cleanup) {
            AppLogger.warn("receivePaymentForGoods",
                "Channel cleanup failed after error: " + cleanup.getMessage());
        }
    }
}