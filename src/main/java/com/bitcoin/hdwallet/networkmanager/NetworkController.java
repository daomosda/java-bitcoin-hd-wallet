/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
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
 * @author DAOMOSDA
 */

// BitcoinWalletApp.java — integrate payment handlers

public class NetworkController {

    private static String className = "NetworkController";
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
            HdAddressManager     addrMgr,
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
        System.out.println("1) Merchant: receive payment for goods");
        System.out.println("2) Merchant: receive payment (API/custom capacity)");
        System.out.println("0) Exit");
        System.out.print("Select option: ");
    }
    private void printCustomerMenu() {
        System.out.println("\n======================================");
        System.out.println("1) Customer: pay for goods");
        System.out.println("0) Exit");
        System.out.print("Select option: ");
    }
    // ─────────────────────────────────────────────────────────────────
    // Option 9 — Merchant receives payment (fixed capacity)
    // ─────────────────────────────────────────────────────────────────

    private void receiveMerchantPayment() {
        try {
            int chainHeight = rpc.getBlockCount();
            AppLogger.info(className, "Starting merchant payment flow at height={}", chainHeight);

            merchantHandler.receivePaymentForGoods(chainHeight);

            AppLogger.info(className, "Payment received successfully.");
        } catch (PaymentException e) {
            AppLogger.error(className, "Payment failed at stage: {}",  "[" + e.getStage() + "]: "
                + e.getMessage());
        } catch (Exception e) {
            AppLogger.error(className, "Unexpected error: {}", e.getMessage());
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
            AppLogger.info(className, "Starting API payment flow: capacity={} height={}",
                capacitySat, chainHeight);

            merchantHandler.receivePaymentAPIs(capacitySat, chainHeight);

            AppLogger.info(className, "API payment received successfully.");
        } catch (PaymentException e) {
            AppLogger.error(className, "Payment failed at stage {}",  "[" + e.getStage() + "]: "
                + e.getMessage());
        } catch (Exception e) {
            AppLogger.error(className, "Unexpected error: {}", e.getMessage());
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
                AppLogger.warn(className, "Channel ID is required.");
                return;
            }

            int chainHeight = rpc.getBlockCount();
            AppLogger.info(className, "Starting customer payment: channelId={} height={}",
                channelId, chainHeight);

            customerHandler.payForGoods(channelId, chainHeight, pubKeyBytes);

            AppLogger.info(className, "Payment sent successfully.");

            // top up address pool — change address was consumed
            addrMgr.ensureLookahead();

        } catch (PaymentException e) {
            AppLogger.error(className, "Payment failed: {}", e.getMessage());
        } catch (Exception e) {
            AppLogger.error(className, "Unexpected error: {}", e.getMessage());
        }
    }    
}