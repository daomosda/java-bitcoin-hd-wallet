package com.bitcoin.hdwallet.tranxcontrol;

import com.bitcoin.hdwallet.model.Psbt;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.crypto.Bech32;
import com.bitcoin.hdwallet.model.Psbt.PsbtInput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.AppNWKConfig;
import com.bitcoin.hdwallet.core.Bip32HDWallet;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.crypto.SegWitAddress.Network;
import com.bitcoin.hdwallet.model.HDKey;
import com.bitcoin.hdwallet.keymanagement.MasterKeyProvider;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import com.bitcoin.hdwallet.model.Utxo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author DAOMOSDA
 */
public class OwnSigner {
    
    private static final X9ECParameters SECP256K1 = SECNamedCurves.getByName("secp256k1");
    private static final BigInteger CURVE_N = SECP256K1.getN();
    private static final SecureRandom RNG = new SecureRandom();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }
    
    private final BitcoinRpcClient walletRpc;
    private final BitcoinRpcClient nodeRpc;
    private final FundingGuard fundingGuard;
    private final CoinSelector coinSelector;
    private final HdAddressManager addressManager;
    
    private final Bip32HDWallet     masterKey;
    private final Network           network;
    // ============================================================
    // 1. CREATE FUNDED PSBT
    // ============================================================
    
    private static final BigDecimal MIN_CONSOLIDATION_VALUE =
            new BigDecimal("0.001");

    // Fee rate sat/vbyte
    private static final long FEE_RATE_SAT_PER_VBYTE = 10L;
    // Use MAX_RBF sequence within the 30‑bit range
    private static final int MAX_RBF_SEQUENCE = 0xFFFFFFFD & 0x3FFFFFFF;  // 0x3FFFFFFD


    public OwnSigner(BitcoinRpcClient walletRpc, BitcoinRpcClient nodeRpc, 
            FundingGuard fundingGuard, CoinSelector coinSelector, 
            HdAddressManager addressManager, Bip32HDWallet masterKey,
            Network network) {
        this.walletRpc = walletRpc;
        this.nodeRpc = nodeRpc;
        this.fundingGuard = fundingGuard;
        this.coinSelector = coinSelector;
        this.addressManager = addressManager;
        this.masterKey      = masterKey;
        this.network        = network;
    }
    
    public PsbtCreateResult createFundedPsbt(
            String destinationAddress,
            BigDecimal amountBtc,
            String changeAddress
    ) throws Exception {
        AppLogger.info("[sendCoins] destAddress={}", destinationAddress);
        AppLogger.info("[sendCoins] changeAddress={}", changeAddress);
        AppLogger.info("[sendCoins] amount={}", amountBtc);
              
        JSONObject res = (JSONObject) BitcoinRpcClient.executeRpc(
                "walletcreatefundedpsbt",
                /* inputs */ new JSONArray(),   // let Core choose UTXOs
                /* outputs */ new JSONObject().put(destinationAddress, amountBtc),
                /* locktime */ 0,
                /* options */ new JSONObject()
                        .put("includeWatching", false)
                        .put("changeAddress", changeAddress)
                        .put("replaceable", false)
                        // sat/vB
                        .put("fee_rate", 1),
                /* bip32derivs */ true
        );

        String unsignedPsbtBase64 = res.getString("psbt");
        System.out.println("[sendCoins] unsigned PSBT len=" + unsignedPsbtBase64.length()
                + " prefix=" + unsignedPsbtBase64.substring(0, Math.min(16, unsignedPsbtBase64.length())));

        BigDecimal fee =
            res.getBigDecimal("fee");

        int changePos =
            res.getInt("changepos");

        AppLogger.info("Created PSBT: fee={} BTC changepos={}", fee.toString() + ","  + Integer.toString(changePos));

        return new PsbtCreateResult(
            unsignedPsbtBase64,
            fee,
            changePos
        );
    }
    
    // ============================================================
    // 2. DECODE PSBT
    // ============================================================

    public JSONObject decodePsbt(String psbtBase64)
        throws Exception {

        JSONObject decoded = (JSONObject) BitcoinRpcClient.executeRpc(
            "decodepsbt",
            psbtBase64
        );

        System.out.println(
            decoded.toString(2)
        );
        
        JSONObject tx = decoded.getJSONObject("tx");

        AppLogger.info(
            "Decoded txid={}, version={}",
            tx.getString("txid") + ", " +
            tx.getInt("version")
        );

        JSONArray vin  = tx.getJSONArray("vin");
        JSONArray vout = tx.getJSONArray("vout");

        AppLogger.info(
            "Transaction contains {} inputs and {} outputs",
            vin.length() + ", " +
            vout.length()
        );

        JSONArray psbtInputs  = decoded.getJSONArray("inputs");
        JSONArray psbtOutputs = decoded.getJSONArray("outputs");

        for (int i = 0; i < psbtInputs.length(); i++) {

            JSONObject in = psbtInputs.getJSONObject(i);

            JSONArray derivs = in.optJSONArray("bip32_derivs");

            if (derivs == null) {
                AppLogger.warn(
                    "No derivations for input {}",
                    Integer.toString(i)
                );
                continue;
            }

            for (int j = 0; j < derivs.length(); j++) {

                JSONObject d = derivs.getJSONObject(j);

                String pubkey = d.getString("pubkey");
                String path   = d.getString("path");
                String master = d.getString("master_fingerprint");

                AppLogger.info(
                    "input={} pubkey={} path={} master={}",
                    i + ", " +
                    pubkey + ", " +
                    path + ", " +
                    master
                );
            }
        }

        // ✅ RETURN FULL decodepsbt RESULT
        return decoded;
    }
    
    // ============================================================
    // 4. COMPUTE BIP143 SIGHASH
    // ============================================================

    public byte[] computeSegwitV0Sighash(
            TxData tx, int inputIndex, byte[] scriptCode, long amountSat, int sighashType) throws Exception {

        return TxSigner.computeSegwitV0Sighash(
            tx,
            inputIndex,
            scriptCode,
            amountSat,
            sighashType
        );
    }
    
    // ============================================================
    // 5. ECDSA SIGN
    // ========================================================
    private String signInput(byte[] privKey, JSONObject decoded) throws Exception {
        // 1. Input 0 from decodepsbt
        JSONObject input0 = decoded.getJSONArray("inputs").getJSONObject(0);
        JSONObject witnessUtxo = input0.getJSONObject("witness_utxo");

        // 2. Amount (BTC) -> sats
        BigDecimal amountBtc = witnessUtxo.getBigDecimal("amount"); // e.g. 12.5
        long inputValueSat = amountBtc
                .multiply(BigDecimal.valueOf(100_000_000L))
                .longValueExact();
        System.out.println("[OwnSigner => signInput()] inputValueSat: " + inputValueSat);

        // 3. ScriptPubKey (for logging / sanity)
        String scriptPubKeyHex = witnessUtxo
                .getJSONObject("scriptPubKey")
                .getString("hex");
        byte[] scriptPubKey = HexUtils.hexToBytes(scriptPubKeyHex);
        System.out.println("[OwnSigner => signInput()] scriptPubKey: " + Arrays.toString(scriptPubKey));

        // 4. Compute SegWit v0 sighash directly from decoded PSBT
        byte[] sighash = TxSigner.computeSegwitV0SighashFromDecoded(decoded, 0);

        // 5. Sign with ECDSA over secp256k1
        byte[] sigDer = signECDSA(privKey, sighash);

        // 6. Append SIGHASH_ALL (0x01)
        byte[] sigWithType = Arrays.copyOf(sigDer, sigDer.length + 1);
        sigWithType[sigDer.length] = 0x01;

        // 7. Return as hex (for walletprocesspsbt / partial_sigs)
        return HexUtils.bytesToHex(sigWithType);
    }      
   
    public static byte[] computeSegwitV0SighashFromDecoded(JSONObject decoded, int inputIndex) {
        JSONObject tx = decoded.getJSONObject("tx");

        int version = tx.getInt("version");
        long locktime = tx.getLong("locktime");

        JSONArray vin = tx.getJSONArray("vin");
        JSONArray vout = tx.getJSONArray("vout");

        byte[] hashPrevouts = buildHashPrevouts(vin);
        byte[] hashSequence = buildHashSequence(vin);
        byte[] hashOutputs  = buildHashOutputs(vout);

        JSONObject in = vin.getJSONObject(inputIndex);
        String prevTxidHex = in.getString("txid");
        int prevVout = in.getInt("vout");
        long sequence = in.getLong("sequence");

        byte[] prevTxidLE = reverseBytes(hexToBytes(prevTxidHex));
        ByteArrayOutputStream outpoint = new ByteArrayOutputStream();
        try {
            outpoint.write(prevTxidLE);
            outpoint.write(intToLE(prevVout));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JSONObject inputDecoded = decoded.getJSONArray("inputs").getJSONObject(inputIndex);
        JSONObject witnessUtxo = inputDecoded.getJSONObject("witness_utxo");
        long valueSat = btcToSat(BigDecimal.valueOf(witnessUtxo.getDouble("amount")));

        String spkHex = witnessUtxo.getJSONObject("scriptPubKey").getString("hex");
        byte[] scriptPubKey = hexToBytes(spkHex);
        byte[] pubKeyHash20 = extractP2wpkhHash(scriptPubKey);
        byte[] scriptCode = buildP2wpkhScriptCode(pubKeyHash20);

        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        try {
            preimage.write(intToLE(version));
            preimage.write(hashPrevouts);
            preimage.write(hashSequence);
            preimage.write(outpoint.toByteArray());
            preimage.write(compactSize(scriptCode.length));
            preimage.write(scriptCode);
            preimage.write(longToLE(valueSat));
            preimage.write(intToLE((int) sequence));
            preimage.write(hashOutputs);
            preimage.write(intToLE((int) locktime));
            preimage.write(intToLE(1)); // SIGHASH_ALL
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return doubleSha256(preimage.toByteArray());
    }
    
    private static byte[] buildHashPrevouts(JSONArray vin) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vin.length(); i++) {
                JSONObject in = vin.getJSONObject(i);
                String txidHex = in.getString("txid");
                int vout = in.getInt("vout");

                // txid is big-endian in JSON, needs little-endian for hashPrevouts
                byte[] txidLE = reverseBytes(hexToBytes(txidHex));
                baos.write(txidLE);           // 32 bytes
                baos.write(intToLE(vout));    // 4 bytes
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }

    private static byte[] buildHashSequence(JSONArray vin) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vin.length(); i++) {
                JSONObject in = vin.getJSONObject(i);
                long sequence = in.getLong("sequence");
                baos.write(intToLE((int) sequence)); // 4 bytes each
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }
    
    private static byte[] buildHashOutputs(JSONArray vout) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < vout.length(); i++) {
                JSONObject out = vout.getJSONObject(i);
                double btcValue = out.getDouble("value");
                long valueSat = Math.round(btcValue * 100_000_000L);

                String spkHex = out.getJSONObject("scriptPubKey").getString("hex");
                byte[] spk = hexToBytes(spkHex);

                // Output format: value (8 bytes LE) + scriptPubKey (varint + bytes)
                baos.write(longToLE(valueSat));
                baos.write(compactSize(spk.length));
                baos.write(spk);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return doubleSha256(baos.toByteArray());
    }
    
    private static byte[] extractP2wpkhHash(byte[] spk) {
        // P2WPKH format: 0x00 0x14 {20-byte pubkey hash}
        if (spk.length != 22 || spk[0] != 0x00 || spk[1] != 0x14) {
            throw new IllegalArgumentException(
                "Expected P2WPKH scriptPubKey (22 bytes starting 0014), got " + HexUtils.bytesToHex(spk));
        }
        return Arrays.copyOfRange(spk, 2, 22);
    }
    
    private static byte[] intToLE(int value) {
        return new byte[] {
            (byte) (value       & 0xFF),
            (byte) (value >>> 8 & 0xFF),
            (byte) (value >>>16 & 0xFF),
            (byte) (value >>>24 & 0xFF)
        };
    }
    
    private static byte[] longToLE(long value) {
        return new byte[] {
            (byte) (value        & 0xFF),
            (byte) (value >>>  8 & 0xFF),
            (byte) (value >>> 16 & 0xFF),
            (byte) (value >>> 24 & 0xFF),
            (byte) (value >>> 32 & 0xFF),
            (byte) (value >>> 40 & 0xFF),
            (byte) (value >>> 48 & 0xFF),
            (byte) (value >>> 56 & 0xFF)
        };
    }
    
    private static byte[] compactSize(int len) {
        if (len < 253) {
            return new byte[] {(byte) len};
        } else if (len <= 0xFFFF) {
            return new byte[] {
                (byte) 0xFD, 
                (byte) (len & 0xFF), 
                (byte) ((len >>> 8) & 0xFF)
            };
        } else if (len <= 0xFFFFFFFFL) {
            return new byte[] {
                (byte) 0xFE,
                (byte) (len & 0xFF),
                (byte) ((len >>> 8) & 0xFF),
                (byte) ((len >>> 16) & 0xFF),
                (byte) ((len >>> 24) & 0xFF)
            };
        } else {
            throw new IllegalArgumentException("Length too large for compactSize: " + len);
        }
    }
    
    private static byte[] reverseBytes(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private static byte[] doubleSha256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(sha256.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static byte[] buildP2wpkhScriptCode(byte[] pubkeyHash20) {
        // OP_DUP OP_HASH160 {20-byte-pubkey-hash} OP_EQUALVERIFY OP_CHECKSIG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(0x76); // OP_DUP
            baos.write(0xa9); // OP_HASH160
            baos.write(0x14); // PUSH 20 bytes
            baos.write(pubkeyHash20); // 20-byte pubkey hash
            baos.write(0x88); // OP_EQUALVERIFY
            baos.write(0xac); // OP_CHECKSIG
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    // Extract 20-byte pubkey hash from P2WPKH scriptPubKey (0014{20bytes})
    public static byte[] extractPubkeyHash(byte[] scriptPubKey) {
        if (scriptPubKey.length != 22 || scriptPubKey[0] != 0x00 || scriptPubKey[1] != 0x14) {
            throw new IllegalArgumentException("Not a P2WPKH scriptPubKey");
        }
        return Arrays.copyOfRange(scriptPubKey, 2, 22);
    }
    
    // ============================================================
    // 6. ADD PARTIAL SIGNATURE TO PSBT
    // ============================================================
    //
    // IMPORTANT:
    // This assumes you already have a lightweight
    // PSBT updater utility.
    //
    // You should NOT rebuild the tx.
    //
    // ============================================================

    public String addPartialSignature(
            String psbtBase64,
            int inputIndex,
            byte[] pubKeyCompressed,
            byte[] signature
    ) throws Exception {        
        
        Psbt psbt = Psbt.fromBase64(psbtBase64);           
        
        System.out.println("[sendCoins] parsed inputs=" + psbt.getInputs().size()
                + " outputs=" + psbt.getOutputs().size());

        if (psbt.getInputs().isEmpty()) {
            throw new IllegalStateException("PSBT has no inputs after parse");
        }
        
        PsbtUpdater updater = new PsbtUpdater(psbt);

        updater.addPartialSignature(
            inputIndex,
            pubKeyCompressed,
            signature
        );

        return updater.toBase64();
    }
    
    // ============================================================
    // 7. FINALIZE PSBT
    // ============================================================

    public String finalizePsbt(String signedPsbt) throws Exception {

        JSONObject result =
            (JSONObject) BitcoinRpcClient.executeRpc(
                "finalizepsbt",
                signedPsbt,
                false   // <<< don't extract yet
            );

        System.out.println("[finalizePsbt] " + result.toString(2));

        boolean complete = result.getBoolean("complete");

        if (!complete) {
            // Keep the PSBT so we can inspect it
            String psbtStill = result.getString("psbt");
            System.out.println("[finalizePsbt] Incomplete PSBT (still): " + psbtStill);
            throw new IllegalStateException("PSBT not complete");
        }

        return result.getString("hex");
    }
    
    // ============================================================
    // 8. SEND RAW TRANSACTION
    // ============================================================

    public String broadcastTxData(
            String txHex
    ) throws Exception {

        return (String)
            BitcoinRpcClient.executeRpc(
                "sendrawtransaction",
                txHex
            );
    }
    
    public static long btcToSat(BigDecimal btc) {

        return btc
                .multiply(BigDecimal.valueOf(100_000_000L))
                .longValueExact();
    }
    
    // In OwnSigner.sendCoins() — before fundrawtransaction or walletcreatefundedpsbt
    public void verifyUtxoSolvable(String txid, int vout) throws Exception {
        JSONArray utxos = (JSONArray) BitcoinRpcClient.executeRpc(
                "listunspent", 0, 9_999_999,
                new JSONArray(), true,
                new JSONObject().put("minimumAmount", 0));

        for (int i = 0; i < utxos.length(); i++) {
            JSONObject u = utxos.getJSONObject(i);
            if (u.getString("txid").equals(txid) && u.getInt("vout") == vout) {
                boolean solvable    = u.optBoolean("solvable",    false);
                boolean spendable   = u.optBoolean("spendable",   false);
                String  desc        = u.optString ("desc",        "");
                AppLogger.info("[verify] txid={}:{} solvable={} spendable={} desc={}",
                        txid, vout, solvable, spendable, desc);
                if (!solvable) {
                    throw new IllegalStateException(
                            "UTXO is not solvable — missing wpkh descriptor: "
                            + txid + ":" + vout);
                }
                return;
            }
        }
        AppLogger.warn("[verify] UTXO not found in listunspent: {}:{}", txid, vout);
    }
    
    public String sendCoins(String destAddress, BigDecimal amountBtc, 
            String changeAddress, int targetBlocks) throws Exception {
        
        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info("[sendCoins] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info("[sendCoins] Funding confirmed: {}", funding);

        if (funding.requiredMining()) {
            AppLogger.warn("[sendCoins] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));
        
        // ── ✅ Ensure all inputs have solving data before building PSBT ────────
        ensureInputsHaveSolvingData(selectedUtxos, masterKey, network);

        // 1) Create funded PSBT from watch-only wallet
        JSONObject createRes = (JSONObject) BitcoinRpcClient.executeRpc(
            "walletcreatefundedpsbt",
            new JSONArray(),
            new JSONObject().put(destAddress, amountBtc.toPlainString()),
            0,
            new JSONObject()
                .put("includeWatching", true)
                .put("changeAddress", changeAddress),
            true // bip32derivs
        );
        String unsignedPsbtBase64 = createRes.getString("psbt");

        // 2) Decode once for convenience (what you're already doing)
        JSONObject decoded = (JSONObject) BitcoinRpcClient.executeRpc(
            "decodepsbt",
            unsignedPsbtBase64
        );
        
        // 3) Sign with your own signer and update PSBT
        String signedPsbtBase64 = signPsbtWithOwnSigner(unsignedPsbtBase64, decoded);
        //System.out.println("[signedPsbtBase64] " + signedPsbtBase64);

        // Optional: verify partial_signatures present
        JSONObject decodedSigned = (JSONObject) BitcoinRpcClient.executeRpc(
            "decodepsbt",
            signedPsbtBase64
        );
        //System.out.println("[decodepsbt signed] " + decodedSigned.toString(2));

        // 4) Finalize and broadcast via Core
        JSONObject fin = (JSONObject) BitcoinRpcClient.executeRpc(
            "finalizepsbt",
            signedPsbtBase64,
            true // extract
        );
        if (!fin.getBoolean("complete")) {
            throw new IllegalStateException("PSBT not complete");
        }
        String hex = fin.getString("hex");
        String txid = (String) BitcoinRpcClient.executeRpc("sendrawtransaction", hex);     
        
        return txid;
    }
    
    /*
    public String sendBuiltTransaction(String unsignedPsbtBase64) throws Exception {
        // 2) Decode once for convenience (what you're already doing)
        JSONObject decoded = (JSONObject) BitcoinRpcClient.executeRpc(
            "decodepsbt",
            unsignedPsbtBase64
        );

        // 3) Sign with your own signer and update PSBT
        String signedPsbtBase64 = signPsbtWithOwnSigner(unsignedPsbtBase64, decoded);
        //System.out.println("[signedPsbtBase64] " + signedPsbtBase64);

        // Optional: verify partial_signatures present
        JSONObject decodedSigned = (JSONObject) BitcoinRpcClient.executeRpc(
            "decodepsbt",
            signedPsbtBase64
        );
        //System.out.println("[decodepsbt signed] " + decodedSigned.toString(2));

        // 4) Finalize and broadcast via Core
        JSONObject fin = (JSONObject) BitcoinRpcClient.executeRpc(
            "finalizepsbt",
            signedPsbtBase64,
            true // extract
        );
        if (!fin.getBoolean("complete")) {
            throw new IllegalStateException("PSBT not complete");
        }
        String hex = fin.getString("hex");
        String txid = (String) BitcoinRpcClient.executeRpc("sendrawtransaction", hex);
        System.out.println("Broadcasted txid=" + txid);
                
        System.out.println("SUCCESS txid=" + txid);
        
        return txid;
    }
    */
    
    public String sendCoins(
        String        destAddress,
        BigDecimal    amountBtc,
        String        changeAddress,
        Bip32HDWallet masterKey,
        Network       network,
        int targetBlocks) throws Exception {
        
        // ✅ Verify change address is not a mining address
        AppLogger.info("[sendCoins] destAddress={}",   destAddress);
        AppLogger.info("[sendCoins] changeAddress={}",  changeAddress);
        AppLogger.info("[sendCoins] amountBtc={}",      amountBtc);

        if (changeAddress == null || changeAddress.isBlank()) {
            throw new IllegalStateException(
                    "[sendCoins] changeAddress is null or blank."
                    + " Call addressManager.getNextChangeAddress() first.");
        }
        
        // ── 1. Estimate fee first ─────────────────────────────────────────────
        BigDecimal estimatedFee = fundingGuard.estimateFee(targetBlocks);
        AppLogger.info("[sendCoins] estimatedFee={} BTC", estimatedFee);

        // ── 2. ✅ Ensure funded — mines if regtest and underfunded ────────────
        FundingGuard.FundingResult funding = fundingGuard.ensureFunded(
                amountBtc, estimatedFee);

        AppLogger.info("[sendCoins] Funding confirmed: {}", funding);

        if (funding.requiredMining()) {
            AppLogger.warn("[sendCoins] Mined {} block(s) to fund transaction.",
                    funding.roundsMined());
        }

        // ── 3. Select UTXOs from confirmed spendable set ──────────────────────
        List<Utxo> selectedUtxos = coinSelector.select(amountBtc.add(estimatedFee));

        // ── ✅ Ensure all inputs have solving data before building PSBT ────────
        ensureInputsHaveSolvingData(selectedUtxos, masterKey, network);   

        // ── Create funded PSBT ────────────────────────────────────────────────
        JSONObject createRes = (JSONObject) BitcoinRpcClient.executeRpc(
                "walletcreatefundedpsbt",
                buildInputsArray(selectedUtxos),
                new JSONObject().put(destAddress, amountBtc.toPlainString()),
                0,
                new JSONObject()
                        .put("includeWatching", true)
                        .put("changeAddress",   changeAddress),
                true // bip32derivs
        );
        
        // ✅ Verify Core used our change address
        int changePos = createRes.optInt("changepos", -1);
        AppLogger.info("[sendCoins] changepos={} changeAddress={}",
                changePos, changeAddress);
        
        AppLogger.info("[sendCoins] PSBT created. fee={} changepos={}",
            createRes.getBigDecimal("fee"),
            createRes.optInt("changepos", -1));

        String unsignedPsbt = createRes.getString("psbt");

        // ── Decode, sign, finalize, broadcast ────────────────────────────────
        JSONObject decoded      = (JSONObject) BitcoinRpcClient.executeRpc(
                "decodepsbt", unsignedPsbt);
        String     signedPsbt  = signPsbtWithOwnSigner(unsignedPsbt, decoded);
        
        JSONArray  txVout  = decoded.getJSONObject("tx").getJSONArray("vout");

        AppLogger.info("[sendCoins] PSBT outputs:");
        for (int i = 0; i < txVout.length(); i++) {
            JSONObject out     = txVout.getJSONObject(i);
            String     outAddr = out.getJSONObject("scriptPubKey")
                                    .optString("address", "");
            BigDecimal outAmt  = out.getBigDecimal("value");
            boolean    isChange = outAddr.equals(changeAddress);
            AppLogger.info("[sendCoins]   vout={} addr={} amount={} isOurChange={}",
                    i, outAddr, outAmt, isChange);
        }

        if (changePos < 0) {
            AppLogger.warn("[sendCoins] No change output — full amount consumed by fee?");
        }

        JSONObject fin = (JSONObject) BitcoinRpcClient.executeRpc(
                "finalizepsbt", signedPsbt, true);

        if (!fin.getBoolean("complete")) {
            throw new IllegalStateException("PSBT not complete");
        }

        String txid = (String) BitcoinRpcClient.executeRpc(
                "sendrawtransaction", fin.getString("hex"));
                
        return txid;
    }
        
    // ─────────────────────────────────────────────────────────────────────────────

    private JSONArray buildInputsArray(List<Utxo> utxos) {
        JSONArray inputs = new JSONArray();
        for (Utxo u : utxos) {
            inputs.put(new JSONObject()
                    .put("txid", u.txid())
                    .put("vout", u.getVout()));
        }
        return inputs;
    }
    
    public String signPsbtWithOwnSigner(String unsignedPsbtBase64, JSONObject decoded) throws Exception {

        Psbt psbt = Psbt.parseBase64(unsignedPsbtBase64);

        // For now we assume single-input, single-sig P2WPKH
        PsbtInput in0 = psbt.getInputs().get(0);

        // 1) Extract input value and scriptPubKey from decoded JSON
        JSONObject in0Json = decoded.getJSONArray("inputs").getJSONObject(0);
        JSONObject witnessUtxo = in0Json.getJSONObject("witness_utxo");
        long amountSat = (long) Math.round(witnessUtxo.getDouble("amount") * 100_000_000L);

        String scriptPubKeyHex = witnessUtxo
            .getJSONObject("scriptPubKey")
            .getString("hex");

        // 2) Extract bip32_derivs[0] to know which key to use
        JSONObject deriv = in0Json
            .getJSONArray("bip32_derivs")
            .getJSONObject(0);

        String pubkeyHex = deriv.getString("pubkey");
        String path = deriv.getString("path");                // "m/84h/1h/0h/1/0"
        String masterFpHex = deriv.getString("master_fingerprint");

        System.out.println("[sendCoins] pubkeyHex=" + pubkeyHex);
        System.out.println("[sendCoins] input=0 path=" + path + " masterFp=" + masterFpHex);
        
        // 3) Derive privKey for this path from your own HD wallet
       
        // 3. Sign using derivation path (no Psbt object needed)
        Bip32HDWallet master = MasterKeyProvider.getMasterKey();
        Bip32HDWallet child = master.derivePath(path);
        byte[] privKeyBytes = child.getPrivateKeyBytes();
        String privKeyHex = HexUtils.bytesToHex(privKeyBytes);

        // 4) Build sighash + sign (you already have this)
        String sigHex = signInput(privKeyBytes, decoded); // returns DER + 1-byte sighash

        // 5) Attach as partial signature in PSBT input
        in0.addPartialSignature(HexUtils.hexToBytes(pubkeyHex),
                                HexUtils.hexToBytes(sigHex));

        // 6) Serialize back to base64
        return psbt.toBase64();
    }        
    
    /**
    * Sign a 32-byte Bitcoin sighash with ECDSA over secp256k1.
    * @param privKey 32-byte private key (big-endian).
    * @param sighash 32-byte message hash to sign.
    * @return DER-encoded ECDSA signature (no sighash type byte).
    */
   public static byte[] signECDSA(byte[] privKey, byte[] sighash) {
       if (privKey.length != 32) {
           throw new IllegalArgumentException("Private key must be 32 bytes");
       }
       if (sighash.length != 32) {
           throw new IllegalArgumentException("Sighash must be 32 bytes");
       }

       BigInteger d = new BigInteger(1, privKey);
       ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(d, new org.bouncycastle.crypto.params.ECDomainParameters(
               SECP256K1.getCurve(), SECP256K1.getG(), SECP256K1.getN(), SECP256K1.getH()));

       ECDSASigner signer = new ECDSASigner();
       signer.init(true, privParams);

       BigInteger[] rs = signer.generateSignature(sighash);
       BigInteger r = rs[0];
       BigInteger s = rs[1];

       // Enforce low-S value (BIP62-style canonical signatures)
       BigInteger halfN = CURVE_N.shiftRight(1);
       if (s.compareTo(halfN) > 0) {
           s = CURVE_N.subtract(s);
       }

       return derEncode(r, s);
   }

   // DER encode SEQUENCE { r INTEGER, s INTEGER }
   private static byte[] derEncode(BigInteger r, BigInteger s) {
       byte[] rBytes = r.toByteArray();
       byte[] sBytes = s.toByteArray();
       int totalLen = 6 + rBytes.length + sBytes.length; // rough upper bound

       ByteArrayOutputStream baos = new ByteArrayOutputStream(totalLen);
       baos.write(0x30); // SEQUENCE
       int len = 2 + rBytes.length + 2 + sBytes.length;
       baos.write(len);

       // r
       baos.write(0x02); // INTEGER
       baos.write(rBytes.length);
       baos.write(rBytes, 0, rBytes.length);

       // s
       baos.write(0x02); // INTEGER
       baos.write(sBytes.length);
       baos.write(sBytes, 0, sBytes.length);

       return baos.toByteArray();
   }
    
    // ─────────────────────────────────────────────────────────────────────────────
    
    /**
    * Ensures every selected UTXO input has wpkh solving data
    * so Core can estimate transaction size and sign via walletprocesspsbt.
    *
    * Called in sendCoins() before walletcreatefundedpsbt.
    *
    * @param selectedUtxos UTXOs that will be used as inputs
    * @param masterKey     HD master key for deriving pubkeys
    * @param network       current network (REGTEST/TESTNET/MAINNET)
     * @throws java.lang.Exception
    */
   public void ensureInputsHaveSolvingData(
           List<Utxo>    selectedUtxos,
           Bip32HDWallet masterKey,
           Network       network) throws Exception {

       if (selectedUtxos == null || selectedUtxos.isEmpty()) {
           AppLogger.warn("[OwnSigner] ensureInputsHaveSolvingData:"
                   + " no UTXOs provided.");
           return;
       }

       AppLogger.info("[OwnSigner] Checking solving data for {} input(s)...",
               selectedUtxos.size());

       boolean useTestnetEncoding = (network == Network.TESTNET
                                  || network == Network.REGTEST);
       int     coinType           = useTestnetEncoding ? 1 : 0;

       // Derive account xpub once — shared across all inputs
       Bip32HDWallet account84 = masterKey.derivePath(
               String.format("m/84'/%d'/0'", coinType));
       String accountXpub      = account84.toBase58(useTestnetEncoding, true);
       String fingerprint      = String.format("%08x",
               masterKey.getFingerprintValue());

       AppLogger.info("[OwnSigner] fingerprint={} coinType={}",
               fingerprint, coinType);

       // Batch reimport entries — avoids one RPC call per address
       JSONArray reimportBatch = new JSONArray();

       for (Utxo utxo : selectedUtxos) {
           String address = utxo.getAddress();

           // ── Check current solving status ──────────────────────────────────
           JSONObject addrInfo = (JSONObject) BitcoinRpcClient.executeRpc(
                   "getaddressinfo", address);

           boolean solvable  = addrInfo.optBoolean("solvable", false);
           String  desc      = addrInfo.optString("desc", "");
           boolean hasHdPath = desc.contains("/84h/");

           AppLogger.info("[OwnSigner] addr={} solvable={} hasHdPath={} desc={}",
                   address, solvable, hasHdPath,
                   desc.length() > 50 ? desc.substring(0, 50) + "..." : desc);

           if (solvable && hasHdPath) {
               AppLogger.info("[OwnSigner] ✅ {} already has solving data.",
                       address);
               continue;
           }

           // ── Needs reimport — get derivation path ──────────────────────────
           AppLogger.warn("[OwnSigner] {} missing solving data — preparing"
                   + " reimport...", address);

           // Extract path from existing descriptor if present
           // e.g. "wpkh([a986c79f/84h/1h/0h/0/3]03abcd...)"
           String path = extractPathFromDesc(desc);

           if (path == null) {
               // Path not in descriptor — try to derive from pubkey match
               path = findPathByPubkeyMatch(address, masterKey, coinType);
           }

           if (path == null) {
               AppLogger.error("[OwnSigner] Cannot determine derivation path"
                       + " for {} — skipping. This input may fail to sign.",
                       address);
               continue;
           }

           AppLogger.info("[OwnSigner] Resolved path for {}: {}",
                   address, path);

           // ── Parse change/index from path ──────────────────────────────────
           // path format: m/84'/1'/0'/change/index
           int[] changeAndIndex = parseChangeAndIndex(path);
           if (changeAndIndex == null) {
               AppLogger.error("[OwnSigner] Could not parse change/index"
                       + " from path: {}", path);
               continue;
           }

           int changeComponent = changeAndIndex[0]; // 0=receive, 1=change
           int index           = changeAndIndex[1];

           AppLogger.info("[OwnSigner] change={} index={}", changeComponent, index);

           // ── Build wpkh([fp/path]xpub/change/index) descriptor ─────────────
           String raw = String.format(
                   "wpkh([%s/84h/%dh/0h]%s/%d/%d)",
                   fingerprint, coinType, accountXpub,
                   changeComponent, index);

           JSONObject descInfo  = (JSONObject) BitcoinRpcClient.executeRpc(
                   "getdescriptorinfo", raw);
           String checksummed   = descInfo.getString("descriptor");

           AppLogger.info("[OwnSigner] Reimporting as: {}...",
                   checksummed.substring(0, Math.min(60, checksummed.length())));

           reimportBatch.put(new JSONObject()
                   .put("desc",      checksummed)
                   .put("timestamp", 0)                // scan from genesis
                   .put("internal",  changeComponent == 1)
                   .put("active",    false)
                   .put("label",     "input-reimport"));
       }

       // ── Send batch import ─────────────────────────────────────────────────
       if (reimportBatch.length() == 0) {
           AppLogger.info("[OwnSigner] All inputs already have solving data ✅");
           return;
       }

       AppLogger.info("[OwnSigner] Reimporting {} input(s) with solving data...",
               reimportBatch.length());

       JSONArray results = (JSONArray) BitcoinRpcClient.executeRpc(
               "importdescriptors", reimportBatch);

       int success = 0;
       int failed  = 0;
       for (int i = 0; i < results.length(); i++) {
           JSONObject r = results.getJSONObject(i);
           if (r.getBoolean("success")) {
               success++;
           } else {
               failed++;
               AppLogger.error("[OwnSigner] Reimport failed at index {}: {}",
                       i, r.optJSONArray("error"));
           }
       }

       AppLogger.info("[OwnSigner] Reimport complete: {} success {} failed",
               success, failed);

       // ── Final verification ────────────────────────────────────────────────
       for (Utxo utxo : selectedUtxos) {
           JSONObject verify   = (JSONObject) BitcoinRpcClient.executeRpc(
                   "getaddressinfo", utxo.getAddress());
           boolean    solvable = verify.optBoolean("solvable", false);
           String     newDesc  = verify.optString("desc", "");

           AppLogger.info("[OwnSigner] Final check addr={} solvable={} desc={}",
                   utxo.getAddress(), solvable,
                   newDesc.length() > 50 ? newDesc.substring(0, 50) + "..." : newDesc);

           if (!solvable) {
               throw new IllegalStateException(
                       "[OwnSigner] Input still not solvable after reimport: "
                       + utxo.getAddress()
                       + " — check fingerprint and derivation path.");
           }
       }

       AppLogger.info("[OwnSigner] ✅ All inputs have solving data.");
   }

    /**
     * Extracts the change/index suffix from a derivation path.
     * e.g. "m/84'/1'/0'/0/3" → [0, 3]
     *      "m/84'/1'/0'/1/7" → [1, 7]
     */
    private int[] parseChangeAndIndex(String path) {
        try {
            // Normalise — replace h with ' for splitting
            String normalised = path.replace("h", "'");
            String[] parts    = normalised.split("/");

            // Expect at least: m / 84' / 1' / 0' / change / index
            if (parts.length < 6) {
                AppLogger.warn("[OwnSigner] Path too short to parse: {}", path);
                return null;
            }

            int change = Integer.parseInt(parts[parts.length - 2]);
            int index  = Integer.parseInt(parts[parts.length - 1]);
            return new int[]{change, index};

        } catch (NumberFormatException e) {
            AppLogger.error("[OwnSigner] Cannot parse change/index from path {}: {}",
                    path, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the non-hardened derivation suffix from an existing descriptor.
     * e.g. desc="wpkh([a986c79f/84h/1h/0h/0/3]03ab...)"
     *      → "m/84'/1'/0'/0/3"
     */
    private String extractPathFromDesc(String desc) {
        if (desc == null || desc.isBlank()) return null;
        try {
            // Format: wpkh([fingerprint/path]pubkey)
            int start = desc.indexOf('[');
            int end   = desc.indexOf(']');
            if (start < 0 || end < 0 || end <= start) return null;

            String inner = desc.substring(start + 1, end);
            // inner = "a986c79f/84h/1h/0h/0/3"
            int slashIdx = inner.indexOf('/');
            if (slashIdx < 0) return null;

            String pathPart = inner.substring(slashIdx + 1); // "84h/1h/0h/0/3"
            // Convert to standard notation
            return "m/" + pathPart.replace("h", "'");

        } catch (Exception e) {
            AppLogger.warn("[OwnSigner] extractPathFromDesc failed: {}",
                    e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Last resort — scans receive and change paths to find which index
     * produces the pubkey matching this address.
     *
     * Scans up to SCAN_LIMIT indices on both receive (0) and change (1) chains.
     */
    private String findPathByPubkeyMatch(
            String        address,
            Bip32HDWallet masterKey,
            int           coinType) {

        final int SCAN_LIMIT = 200;

        AppLogger.info("[OwnSigner] Scanning HD paths to find match for {}...",
                address);

        for (int changeComp = 0; changeComp <= 1; changeComp++) {
            for (int idx = 0; idx < SCAN_LIMIT; idx++) {
                try {
                    String path = String.format(
                            "m/84'/%d'/0'/%d/%d", coinType, changeComp, idx);

                    Bip32HDWallet child   = masterKey.derivePath(path);
                    byte[]        pubKey  = child.getPublicKeyCompressed();
                    String        derived = deriveAddress(pubKey, coinType);

                    if (address.equals(derived)) {
                        AppLogger.info("[OwnSigner] Found match: {} at path {}",
                                address, path);
                        return path;
                    }

                } catch (Exception e) {
                    AppLogger.error("[OwnSigner] Path scan error at change={}"
                            + " idx={}: {}", changeComp, idx, e.getMessage());
                }
            }
        }

        AppLogger.warn("[OwnSigner] No path found for {} within scan limit",
                address);
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Derives a bech32 address from a compressed pubkey.
     * Uses P2WPKH (native SegWit) — matches your BIP84 setup.
     */
    private String deriveAddress(byte[] compressedPubKey, int coinType) {
        try {
            // HASH160 = RIPEMD160(SHA256(pubkey))
            MessageDigest sha256   = MessageDigest.getInstance("SHA-256");
            byte[]        sha256d  = sha256.digest(compressedPubKey);

            RIPEMD160Digest ripemd = new RIPEMD160Digest();
            ripemd.update(sha256d, 0, sha256d.length);
            byte[] hash160 = new byte[20];
            ripemd.doFinal(hash160, 0);

            // Bech32 encode as P2WPKH
            // coinType=1 → testnet/regtest hrp "bcrt" or "tb"
            // coinType=0 → mainnet hrp "bc"
            String hrp = switch (coinType) {
                case 0  -> "bc";
                case 1  -> "bcrt"; // regtest; use "tb" for testnet
                default -> "bcrt";
            };

            return Bech32.encode(hrp, hash160);  //(hrp, 0, hash160);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("[OwnSigner] deriveAddress failed: "
                    + e.getMessage(), e);
        }
    }        
        
    /**
     * Queries Bitcoin Core for all UTXOs belonging to used change addresses.
     */
    private List<Utxo> collectChangeUtxos() throws Exception {

        // Get all used change addresses from DB
        List<String> usedChangeAddresses = getUsedChangeAddresses();

        AppLogger.info("[consolidate] Checking {} used change address(es)...",
                usedChangeAddresses.size());

        if (usedChangeAddresses.isEmpty()) return Collections.emptyList();

        // Query listunspent filtered to change addresses
        JSONArray addrFilter = new JSONArray();
        usedChangeAddresses.forEach(addrFilter::put);

        JSONArray utxos = (JSONArray) BitcoinRpcClient.executeRpc(
                "listunspent",
                1,           // minconf — confirmed only
                9_999_999,   // maxconf
                addrFilter); // address filter

        List<Utxo> result = new ArrayList<>();

        for (int i = 0; i < utxos.length(); i++) {
            JSONObject u      = utxos.getJSONObject(i);
            
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
            
            // Skip dust
            if (amount.compareTo(MIN_CONSOLIDATION_VALUE) < 0) {
                AppLogger.warn("[consolidate] Skipping dust UTXO: {} BTC"
                        + " at {}", amount, u.getString("address"));
                continue;
            }

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
        
        return result;
    }
    
    /**
     * Returns all change addresses that have been marked as used in the DB.
     */
    private List<String> getUsedChangeAddresses() throws Exception {
        return addressManager.getUsedChangeAddresses();
    }
    
    private void ensureAllHaveSolvingData(List<Utxo> utxos)
            throws Exception {

        boolean useTestnetEncoding = (network == Network.TESTNET
                                   || network == Network.REGTEST);
        int     coinType           = useTestnetEncoding ? 1 : 0;

        Bip32HDWallet account84 = masterKey.derivePath(
                String.format("m/84'/%d'/0'", coinType));
        String accountXpub      = account84.toBase58(useTestnetEncoding, true);
        String fingerprint      = String.format("%08x",
                masterKey.getFingerprintValue());

        JSONArray reimportBatch = new JSONArray();

        for (Utxo utxo : utxos) {
            JSONObject info   = (JSONObject) BitcoinRpcClient.executeRpc(
                    "getaddressinfo", utxo.getAddress());
            boolean solvable  = info.optBoolean("solvable", false);
            String  desc      = info.optString("desc", "");
            boolean hasHdPath = desc.contains("/84h/");

            if (solvable && hasHdPath) {
                AppLogger.warn("[consolidate] {} already solvable ✅",
                        utxo.getAddress());
                continue;
            }

            AppLogger.info("[consolidate] Reimporting {} as wpkh(xpub/index)...",
                    utxo.getAddress());

            // Get index from DB
            HDKey keyRecord = addressManager.hdKeyFromAddress(utxo.getAddress());
            if (keyRecord == null) {
                throw new IllegalStateException(
                        "[consolidate] No key record for change address: "
                        + utxo.getAddress());
            }

            String raw = String.format(
                    "wpkh([%s/84h/%dh/0h]%s/1/%d)", // change = 1
                    fingerprint, coinType, accountXpub,
                    keyRecord.getKeyIndex());

            String checksummed = (String) ((JSONObject) BitcoinRpcClient.executeRpc(
                    "getdescriptorinfo", raw)).getString("descriptor");

            reimportBatch.put(new JSONObject()
                    .put("desc",      checksummed)
                    .put("timestamp", 0)
                    .put("internal",  true)   // change = internal
                    .put("active",    false));
        }

        if (reimportBatch.length() > 0) {
            JSONArray results = (JSONArray) BitcoinRpcClient.executeRpc(
                    "importdescriptors", reimportBatch);
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (!r.getBoolean("success")) {
                    AppLogger.warn("[consolidate] Reimport failed at {}: {}",
                            i, r.optJSONArray("error"));
                }
            }
            AppLogger.info("[consolidate] Reimported {} address(es) with"
                    + " solving data.", reimportBatch.length());
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────
    // REPORT
    // ─────────────────────────────────────────────────────────────────────

    private void printConsolidationReport(List<Utxo> utxos) {
        AppLogger.info("[consolidate] ── Change UTXO Report ─────────────");
        BigDecimal total = BigDecimal.ZERO;
        for (Utxo u : utxos) {
            total = total.add(u.getAmount());
            AppLogger.info("[consolidate]   addr={} txid={}:{}"
                    + " amount={} BTC confs={}",
                    u.getAddress(),
                    u.getTxid().substring(0, 8) + "...",
                    u.getVout(),
                    u.getAmount(),
                    u.getConfirmations());
        }
        AppLogger.info("[consolidate]   ─────────────────────────────────");
        AppLogger.info("[consolidate]   Total: {} BTC across {} UTXO(s)",
                total, utxos.size());
        AppLogger.info("[consolidate] ─────────────────────────────────────");
    }
    
   public String convertChangeToSpendableUtxo() throws Exception {

       AppLogger.info("[consolidate] ── Starting Change Consolidation ──────");

       // ── 1. Find all used change addresses with spendable balance ──────────
       List<Utxo> changeUtxos = collectChangeUtxos();

       if (changeUtxos.isEmpty()) {
           AppLogger.info("[consolidate] No spendable change UTXOs found."
                   + " Nothing to consolidate.");
           return null;
       }

       // ── 2. Report what we found ───────────────────────────────────────────
       BigDecimal totalInput = changeUtxos.stream()
               .map(u -> u.getAmount())
               .reduce(BigDecimal.ZERO, BigDecimal::add);

       AppLogger.info("[consolidate] Found {} change UTXO(s) totalling {} BTC",
               changeUtxos.size(), totalInput);
       printConsolidationReport(changeUtxos);

       // ── 3. Ensure all change inputs have wpkh solving data ────────────────
       ensureAllHaveSolvingData(changeUtxos);

       // ── 4. Get a fresh receive address as consolidation target ────────────
       String consolidationAddress = (String) CachedWalletMnemMap.getObject("consolidationAddress");  
       //String consolidationAddress = addressManager.getNextMiningAddress();
       AppLogger.info("[consolidate] Consolidation target address: {}",
               consolidationAddress);       
         
        /*
        // ── Estimate minimum viable amount before calling Core ────────────────
        int        estimatedVBytes = (int)(10.5 + 31 + (68 * changeUtxos.size()));
        long       minFeeSats      = estimatedVBytes * FEE_RATE_SAT_PER_VBYTE;
        BigDecimal minFeeBtc       = satsToBtc(minFeeSats);

        AppLogger.info("[consolidate] Estimated vbytes={} minFee={} BTC"
                + " totalInput={} BTC",
                estimatedVBytes, minFeeBtc, totalInput);

        if (totalInput.compareTo(minFeeBtc) <= 0) {
            throw new IllegalStateException(String.format(
                    "[consolidate] Total input (%s BTC) is too small to cover"
                    + " estimated fee (%s BTC) for %d inputs."
                    + " Accumulate more change before consolidating.",
                    totalInput, minFeeBtc, changeUtxos.size()));
        }
        */
        
        // ── Estimate minimum viable amount before calling Core ────────────────
        int        estimatedVBytes = (int)(10.5 + 31 + (68 * changeUtxos.size()));

        // ✅ Apply 2x safety buffer — actual fee is often double the estimate
        // because Core uses CONSERVATIVE mode and accounts for witness data
        long       minFeeSats      = estimatedVBytes * FEE_RATE_SAT_PER_VBYTE * 2;
        BigDecimal minFeeBtc       = satsToBtc(minFeeSats);

        AppLogger.info(String.format(
                "[consolidate] Estimated vbytes=%d minFee=%s BTC (2x buffer)"
                + " totalInput=%s BTC",
                estimatedVBytes, minFeeBtc, totalInput));

        if (totalInput.compareTo(minFeeBtc) <= 0) {
            throw new IllegalStateException(String.format(
                    "[consolidate] Total input (%s BTC) too small to cover"
                    + " estimated fee with 2x buffer (%s BTC) for %d inputs."
                    + " Accumulate more change before consolidating.",
                    totalInput, minFeeBtc, changeUtxos.size()));
        }

        // ── 6. Build inputs array ─────────────────────────────────────────────
        JSONArray inputs = new JSONArray();
        for (Utxo utxo : changeUtxos) {
            inputs.put(new JSONObject()
                    .put("txid",     utxo.getTxid())
                    .put("vout",     utxo.getVout())
                    .put("sequence", 4294967293L)); // 0xFFFFFFFD — RBF
        }

        // ── 7. Build output — dummy amount, Core will maximize it ────────────
        // subtractFeeFromOutputs tells Core to deduct its fee from output[0]
        // so the final output = totalInput - actualFee
        JSONArray outputs = new JSONArray();
        //outputs.put(new JSONObject().put(consolidationAddress, 0.0001));
        
        outputs.put(new JSONObject().put(consolidationAddress, 
                totalInput.setScale(8, RoundingMode.DOWN))); // ✅ use full input amount
        
        // ── 8. Create PSBT — Core handles fee calculation ─────────────────────
        AppLogger.info("[consolidate] Creating consolidation PSBT...");
        
        Object raw = BitcoinRpcClient.executeRpc(
                "walletcreatefundedpsbt",
                inputs,
                outputs,
                0,
                new JSONObject()
                        .put("includeWatching",        true)
                        .put("changeAddress",           consolidationAddress)
                        .put("subtractFeeFromOutputs",
                                new JSONArray().put(0))
                        .put("conf_target",             6)
                        .put("estimate_mode",           "CONSERVATIVE"),
                true
        );

        if (!(raw instanceof JSONObject psbtResult)) {
            throw new BitcoinRpcException(
                    "[consolidate] walletcreatefundedpsbt unexpected: "
                    + raw.getClass());
        }

        String     psbt       = psbtResult.getString("psbt");
        BigDecimal actualFee  = psbtResult.optBigDecimal("fee", BigDecimal.ZERO);
        BigDecimal actualOut  = totalInput.subtract(actualFee)
                                          .setScale(8, RoundingMode.DOWN);

        AppLogger.info("[consolidate] PSBT created."
                + " actualFee={} BTC actualOutput={} BTC",
                actualFee, actualOut);

        // ── 9. Log outputs for verification ───────────────────────────────────
        JSONObject decoded  = (JSONObject) BitcoinRpcClient.executeRpc(
                "decodepsbt", psbt);
        JSONArray  psbtVout = decoded.getJSONObject("tx").getJSONArray("vout");
       
        //System.out.println("[decodepsbt signed] " + decoded.toString(2));
        
        AppLogger.info("[consolidate] PSBT outputs:");
        for (int i = 0; i < psbtVout.length(); i++) {
            JSONObject out     = psbtVout.getJSONObject(i);
            String     outAddr = out.getJSONObject("scriptPubKey")
                                    .optString("address", "");
            BigDecimal outAmt  = out.getBigDecimal("value");
            AppLogger.info("[consolidate]   vout={} addr={} amount={} BTC",
                    i, outAddr, outAmt);
        }

        JSONArray psbtInputs = decoded.getJSONArray("inputs");  
       
       // ── 10. Sign each input with our HD master key ────────────────────────
       AppLogger.info("[consolidate] Signing {} input(s)...",
               psbtInputs.length());
       
       String signedPsbt = signAllInputs(psbt, psbtInputs); 

       // ── 11. Finalize ──────────────────────────────────────────────────────
       JSONObject fin = (JSONObject) BitcoinRpcClient.executeRpc(
               "finalizepsbt", signedPsbt, true);

       if (!fin.getBoolean("complete")) {
           // Diagnose unsigned inputs
           JSONObject diagDecoded = (JSONObject) BitcoinRpcClient.executeRpc(
                   "decodepsbt", signedPsbt);
           JSONArray  diagInputs  = diagDecoded.getJSONArray("inputs");
           for (int i = 0; i < diagInputs.length(); i++) {
               JSONObject in     = diagInputs.getJSONObject(i);
               boolean    hasSig = in.has("partial_signatures")
                       && !(in.getJSONObject("partial_signatures").length() == 0);
               AppLogger.info("[consolidate] Input {} signed={} addr={}",
                       i, hasSig,
                       i < changeUtxos.size()
                               ? changeUtxos.get(i).getAddress() : "?");
           }
           throw new IllegalStateException(
                   "[consolidate] PSBT finalization incomplete."
                   + " Check signing keys.");
       }

       // ── 12. Broadcast ─────────────────────────────────────────────────────
       String rawHex = fin.getString("hex");
       String txid   = (String) BitcoinRpcClient.executeRpc(
               "sendrawtransaction", rawHex);

       AppLogger.info("[consolidate] ✅ Consolidation broadcasted!"
               + " txid={}", txid);
       AppLogger.info("[consolidate] {} change UTXOs → 1 spendable UTXO",
               changeUtxos.size());
       
       // ── 13. Confirm on regtest ────────────────────────────────────────────
       if (network == Network.REGTEST) {
           String miningAddr = addressManager.getNextMiningAddress();
           nodeRpc.executeRpc("generatetoaddress", 1, miningAddr);
           AppLogger.info("[consolidate] Mined 1 block to confirm.");
       }

       // ── 14. Replenish lookahead ───────────────────────────────────────────
       addressManager.ensureLookahead();

       return txid;
   }

   // ─────────────────────────────────────────────────────────────────────────────

   /**
    * Signs all inputs in the PSBT using the HD master key.
    * Each input's bip32_derivs provides the path to derive the signing key.
    */
   private String signAllInputs(
        String           psbtBase64,
        JSONArray        psbtInputs/*,
        List<Utxo> changeUtxos*/) throws Exception {

        String ourFingerprint = String.format("%08x",
                masterKey.getFingerprintValue());

        AppLogger.info("[consolidate] Signing with fingerprint={}",
                ourFingerprint);

        // ── Skip walletprocesspsbt — watch-only wallet cannot sign ────────────
        // Go straight to manual HD signing
        AppLogger.info("[consolidate] Using manual HD signing"
                + " (watch-only wallet has no private keys).");

        JSONObject decoded    = (JSONObject) BitcoinRpcClient.executeRpc(
                "decodepsbt", psbtBase64);
        JSONArray  decodedIns = decoded.getJSONArray("inputs");

        Psbt psbt = Psbt.parseBase64(psbtBase64);

        for (int i = 0; i < decodedIns.length(); i++) {
            JSONObject psbtInput   = decodedIns.getJSONObject(i);
            JSONArray  bip32Derivs = psbtInput.optJSONArray("bip32_derivs");

            // Skip already signed
            boolean alreadySigned = psbtInput.has("partial_signatures")
                    && !(psbtInput.getJSONObject("partial_signatures").length() == 0);
            if (alreadySigned) {
                AppLogger.warn("[consolidate] Input {} already signed.", i);
                continue;
            }

            if (bip32Derivs == null || bip32Derivs.length() == 0) {
                AppLogger.warn("[consolidate] Input {} has no bip32_derivs"
                        + " — cannot sign.", i);
                continue;
            }

            JSONObject deriv     = bip32Derivs.getJSONObject(0);
            String     path      = deriv.getString("path");
            String     masterFp  = deriv.getString("master_fingerprint");
            String     pubkeyHex = deriv.getString("pubkey");

            AppLogger.info("[consolidate] Input {} path={} masterFp={}",
                    i, path, masterFp);

            if (!ourFingerprint.equalsIgnoreCase(masterFp)) {
                AppLogger.info("[consolidate] Input {} fingerprint mismatch:"
                        + " ours={} psbt={} — skipping.",
                        i, ourFingerprint, masterFp);
                continue;
            }

            if (!psbtInput.has("witness_utxo")) {
                AppLogger.warn("[consolidate] Input {} missing witness_utxo.",
                        i);
                continue;
            }

            // Derive signing key
            Bip32HDWallet childKey     = masterKey.derivePath(path);
            byte[]        privKeyBytes = childKey.getPrivateKeyBytes();
            byte[]        pubKeyBytes  = childKey.getPublicKeyCompressed();

            // Compute sighash and sign
            byte[] sighash     = OwnSigner.computeSegwitV0SighashFromDecoded(
                    decoded, i);
            byte[] sigDer      = OwnSigner.signECDSA(privKeyBytes, sighash);
            byte[] sigWithType = Arrays.copyOf(sigDer, sigDer.length + 1);
            sigWithType[sigDer.length] = 0x01; // SIGHASH_ALL

            AppLogger.info("[consolidate] Input {} signed. sig={}...",
                    i, HexUtils.bytesToHex(sigWithType).substring(0, 10));

            PsbtInput psbtIn = psbt.getInputs().get(i);
            psbtIn.addPartialSignature(pubKeyBytes, sigWithType);
        }

        AppLogger.info("[consolidate] Manual signing complete.");
        return psbt.toBase64();
    }
   
   /**
    * Converts satoshis to BTC.
    * 1 BTC = 100,000,000 satoshis.
    *
    * @param sats satoshi amount
    * @return BTC amount with 8 decimal places
    */
   private static BigDecimal satsToBtc(long sats) {
       return BigDecimal.valueOf(sats)
               .divide(
                   BigDecimal.valueOf(100_000_000L),
                   8,
                   RoundingMode.HALF_UP);
   }
   
   /**
    * Converts BTC to satoshis.
    *
    * @param btc BTC amount
    * @return satoshi amount
    */
   private static long btcToSats(BigDecimal btc) {
       return btc
               .multiply(BigDecimal.valueOf(100_000_000L))
               .setScale(0, RoundingMode.DOWN)
               .longValueExact();
   }
}
