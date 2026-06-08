package com.bitcoin.hdwallet.lightningnetwork;

import com.bitcoin.hdwallet.networkmanager.NetworkManager;
import com.bitcoin.hdwallet.model.InvoicePayload;
import com.bitcoin.hdwallet.model.HTLC;
import com.bitcoin.hdwallet.model.Invoice;
import com.bitcoin.hdwallet.model.Peer;
import com.bitcoin.hdwallet.model.OnionPacket;
import com.bitcoin.hdwallet.crypto.Crypto;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.crypto.HexUtils;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @author DAOMOSDA
 */

// ─── Customer side ───────────────────────────────────────────────────────────

public class CustomerPaymentHandler {

    private NetworkManager   networkManager;
    private LightningService lightningService;
    //private OnionPacket onionService;
    
    public CustomerPaymentHandler() {
    }

    public CustomerPaymentHandler(NetworkManager networkManager,
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
    
    //public void setOnionPacket(OnionPacket onionService) {
    //    this.onionService = onionService;
    //}
    
    public void payForGoods(String channelId, int chainHeight, byte[] pubKeyBytes) throws PaymentException {
        AppLogger.section("Coda — payForGoods");
        
        try {
            // 1. Discover merchant peer and request their pubkey
            //    (channel is already open — channelId passed in from setup phase)
            String bradePeerId = networkManager.findMerchantPeer();
            Peer bradePeer = networkManager.findPeer(bradePeerId).orElseThrow(
                () -> new PaymentException("Merchant peer not found: " + bradePeerId)
            );

            // 2. Introduce ourselves so Brade knows who is paying
            String codaPubKeyStr = HexUtils.bytesToHex(pubKeyBytes);
            networkManager.sendToPeer(bradePeerId, codaPubKeyStr);

            // 3. Request and validate invoice from Brade
            InvoicePayload payload = networkManager.waitForInvoice();
            Invoice invoice = Invoice.toInvoice(payload);            
            
            if (invoice.isExpired()) {
                throw new PaymentException("Received invoice is already expired");
            }

            byte[] bradePubKey = HexUtils.hexToBytes(bradePeer.getPubKey());
            List<byte[]> bradeRoute = List.of(bradePubKey);

            // 4. Wrap the payment in an onion packet for privacy
            OnionPacket onion = OnionPacket.buildRoute(
                bradeRoute,      //List.of(bradePubKey),
                invoice.getAmountSat(),
                invoice.getPaymentHash()
            );

            // 5. Add HTLC — Coda locks funds toward Brade
            long cltvExpiry = chainHeight + LightningPaymentConstants.CLTV_OFFERED_DELTA;
            lightningService.addHTLC(
                channelId,
                new HTLC(
                    Long.toString(IdGenerator.next()),
                    invoice.getPaymentHash(),
                    invoice.getAmountSat() * 1_000L,
                    cltvExpiry,
                    HTLC.Direction.OFFERED
                ),
                onion,
                chainHeight
            );

            // 6. Sign and send commitment to Brade, wait for his revoke_and_ack
            //    signAndSend() does commitAndSign + waitForRevocation internally
            lightningService.signAndSend(channelId);

            // 7. Wait for Brade to reveal the preimage (proof of payment)
            byte[] preimage = lightningService.waitForPreimage(invoice.getPaymentHash());
            verifyPreimage(preimage, invoice.getPaymentHash());

            // 8. Settle the HTLC — Brade's balance increases, Coda's decreases
            lightningService.settleHTLC(channelId, invoice.getPaymentHash(), preimage);

            // 9. Final commitment round — both sides update their channel state
            lightningService.signAndSend(channelId);

            AppLogger.info("payForGoods", "Payment completed successfully");

        } catch (PaymentException e) {
            attemptHtlcCleanup(channelId);
            throw e;
        } catch (LightningServiceException e) {
            attemptHtlcCleanup(channelId);
            throw new PaymentException("Commitment failure during payment", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attemptHtlcCleanup(channelId);
            throw new PaymentException("Interrupted during customer payment flow", e);
        }
    }
    
    private void verifyPreimage(byte[] preimage, byte[] expectedHash)
            throws PaymentException {
        if (!Arrays.equals(Crypto.sha256(preimage), expectedHash)) {
            throw new PaymentException(
                "Preimage verification failed: SHA-256 does not match payment hash"
            );
        }
    }

    private void attemptHtlcCleanup(String channelId) {
        if (channelId == null) return;
        try {
            lightningService.failPendingHtlcs(channelId);
        } catch (LightningServiceException cleanup) {
            AppLogger.warn("payForGoods",
                "Channel cleanup failed after error: " + cleanup.getMessage());
        }
    }
}