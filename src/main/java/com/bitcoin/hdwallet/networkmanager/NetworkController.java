package com.bitcoin.hdwallet.networkmanager;

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import com.bitcoin.hdwallet.lightningnetwork.CustomerPaymentHandler;
import com.bitcoin.hdwallet.lightningnetwork.LightningPaymentConstants;
import com.bitcoin.hdwallet.lightningnetwork.LightningService;
import com.bitcoin.hdwallet.lightningnetwork.MerchantPaymentHandler;
import com.bitcoin.hdwallet.lightningnetwork.PaymentException;
import java.util.Scanner;
/**
 *
 * @author CONALDES
 */

// BitcoinWalletApp.java — integrate payment handlers

public class NetworkController {

    // ── existing fields ───────────────────────────────────────────────
    //private final Database         db;
    private final BitcoinRpcClient rpc;
    //private final ChainIndexStore  store;
    //private final ChainSyncer      syncer;
    private final HdAddressManager   addrMgr;
    
    // ── new lightning fields ──────────────────────────────────────────
    private final MerchantPaymentHandler merchantHandler;
    private final CustomerPaymentHandler customerHandler;
    
    private final byte[] pubkeyBytes;

    // ── Constructor ───────────────────────────────────────────────────

    public NetworkController(
            BitcoinRpcClient     rpc,                            
            HdAddressManager       addrMgr,
            NetworkManager       networkManager,
            LightningService     lightningService,
            byte[] pubkeyBytes
    ) {
        //this.db      = db;
        this.rpc     = rpc;
        //this.store   = store;
        //this.syncer  = syncer;
        this.addrMgr = addrMgr;

        // wire merchant handler
        this.merchantHandler = new MerchantPaymentHandler(
            networkManager, lightningService);

        // wire customer handler
        this.customerHandler = new CustomerPaymentHandler(
            networkManager, lightningService);

        this.pubkeyBytes   = pubkeyBytes;
    }

    // ── Menu ──────────────────────────────────────────────────────────

    public void runMainMCTMenu() throws Exception {
        Scanner scanner = new Scanner(System.in);

        //byte[] pubKeyBytes = keyData.pubKeyBytes;
        while (true) {
            printMerchantMenu();
            String input = scanner.nextLine().trim();

            switch (input) {
                // ── new: lightning payment options ────────────────────
                case "1"  -> receiveMerchantPayment();
                case "2" -> receiveApiPayment(scanner);
                case "0"  -> { System.out.println("Goodbye."); return; }
                default   -> System.out.println("Unknown option.");
            }
        }
    }
    
    public void runMainCTMMenu() throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            printCustomerMenu();
            String input = scanner.nextLine().trim();

            switch (input) {
                // ── new: lightning payment options ────────────────────
                case "1" -> makeCustomerPayment(scanner, this.pubkeyBytes);
                case "0"  -> { System.out.println("Goodbye."); return; }
                default   -> System.out.println("Unknown option.");
            }
        }
    }

    private void printMerchantMenu() {
        System.out.println("\n======================================");
        System.out.println("1)  Merchant: receive payment for goods");
        System.out.println("2) Merchant: receive payment (API/custom capacity)");
        System.out.println("0)  Exit");
        System.out.print("Select option: ");
    }
    private void printCustomerMenu() {
        System.out.println("\n======================================");
        System.out.println("1) Customer: pay for goods");
        System.out.println("0)  Exit");
        System.out.print("Select option: ");
    }
    // ─────────────────────────────────────────────────────────────────
    // Option 9 — Merchant receives payment (fixed capacity)
    // ─────────────────────────────────────────────────────────────────

    private void receiveMerchantPayment() {
        try {
            int chainHeight = rpc.getBlockCount();
            AppLogger.info("[App] Starting merchant payment flow at height={}", chainHeight);

            merchantHandler.receivePaymentForGoods(chainHeight);

            System.out.println("Payment received successfully.");
        } catch (PaymentException e) {
            System.out.println("Payment failed at stage [" + e.getStage() + "]: "
                + e.getMessage());
            AppLogger.error("[App] Merchant payment failed", e);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] Merchant payment error", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 10 — Merchant receives payment (custom capacity)
    // ─────────────────────────────────────────────────────────────────

    private void receiveApiPayment(Scanner scanner) {
        try {
            System.out.print("Channel capacity in sat [1000000]: ");
            String input      = scanner.nextLine().trim();
            double capacitySat = input.isEmpty()
                ? LightningPaymentConstants.CHANNEL_CAPACITY_SAT
                : Double.parseDouble(input);

            int chainHeight = rpc.getBlockCount();
            AppLogger.info("[App] Starting API payment flow: capacity={} height={}",
                capacitySat, chainHeight);

            merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);

            System.out.println("API payment received successfully.");
        } catch (PaymentException e) {
            System.out.println("Payment failed at stage [" + e.getStage() + "]: "
                + e.getMessage());
            AppLogger.error("[App] API payment failed", e);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] API payment error", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Option 11 — Customer pays for goods
    // ─────────────────────────────────────────────────────────────────

    private void makeCustomerPayment(Scanner scanner, byte[] pubKeyBytes) {
        try {
            System.out.print("Channel ID: ");
            String channelId = scanner.nextLine().trim();
            if (channelId.isEmpty()) {
                System.out.println("Channel ID is required.");
                return;
            }

            int chainHeight = rpc.getBlockCount();
            AppLogger.info("[App] Starting customer payment: channelId={} height={}",
                channelId, chainHeight);

            customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);

            System.out.println("Payment sent successfully.");

            // top up address pool — change address was consumed
            addrMgr.ensureLookahead();

        } catch (PaymentException e) {
            System.out.println("Payment failed: " + e.getMessage());
            AppLogger.error("[App] Customer payment failed", e);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            AppLogger.error("[App] Customer payment error", e);
        }
    }    
}