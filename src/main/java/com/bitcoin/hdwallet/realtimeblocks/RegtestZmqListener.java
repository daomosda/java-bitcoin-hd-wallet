/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.realtimeblocks;

import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.chainindexing.BlockDataMapper;
import com.bitcoin.hdwallet.chainindexing.ChainReorgHandler;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.inputcontrol.SharedMonitor;
import com.bitcoin.hdwallet.keymanagement.HdAddressManager;
import com.bitcoin.hdwallet.model.Utxo;
import com.bitcoin.hdwallet.repository.ChainIndexRepository;
import com.bitcoin.hdwallet.utxosinfo.UtxoAnalyzer;
import com.bitcoin.hdwallet.utxosinfo.UtxoSet;
import com.bitcoin.hdwallet.utxosinfo.UtxoSummary;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 *
 * @author DAOMOSDA
 */
public class RegtestZmqListener implements Runnable {
    
    private static String className = "RegtestZmqListener";
    
    private final ChainIndexRepository dbIndexStore;
    private static final int BLOCK_SYNC_THRESHOLD = 10; // configurable
    
    private final BitcoinRpcClient walletRpc;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    
    private final HdAddressManager addressManager;
    private volatile long lastSyncHeight = 0;
    private volatile long lastSyncTime = 0;
    private final Set<String> addressCache; 

    private final SharedMonitor monitor;
    private final int id = 1;

    public RegtestZmqListener(
            BitcoinRpcClient walletRpc,
            HdAddressManager addressManager,
            ChainIndexRepository  dbIndexStore,
            Set<String> addressCache,
            SharedMonitor monitor,
            int startHeight) {
        this.walletRpc = walletRpc;
        this.addressManager = addressManager;
        this.dbIndexStore = dbIndexStore;
        this.addressCache = addressCache;
        this.monitor = monitor;
    }

    @Override
    public void run() {
        
        //try (ZContext context = new ZContext()) {
        try (ZContext context = new ZContext()) {
            // Replace RPC polling with ZMQ (real-time blocks) 
            ZmqConfig zmqConfig = ZmqConfig.defaultRegtest();
            ZMQ.Socket subscriber = context.createSocket(SocketType.SUB);
            subscriber.connect(zmqConfig.rawBlockEndpoint());    //"tcp://127.0.0.1:28332");
            subscriber.subscribe("");                            

            AppLogger.info(className, "run() Listening for REGTEST blocks...");
            
            while (!Thread.currentThread().isInterrupted()) {
                
                // 1. Wait for signal from Controller
                monitor.waitForSignal(id);
                
                int numOfBlocks  = (int) CachedWalletMnemMap.getObject("blocks");
                
                for(int counter = 0; counter < numOfBlocks; counter++) {
                    String topic = subscriber.recvStr();
                    byte[] body = subscriber.recv(0);

                    if (topic != null && body != null && topic.equals("rawblock")) {
                        try {
                            
                            Connection conn = dbIndexStore.DBConn().getConnection();
                            try {
                                // 1. Start DB Transaction

                                dbIndexStore.DBConn().beginTransaction(conn);

                                // 2. Parse the Block
                                BlockData block = parseRawRegtestBlock(body);
                                System.out.println("run() Received: " + block);

                                // 3. Resolve Height (Raw blocks don't have height)
                                // We fetch it via RPC using the Block Hash we just calculated
                                int height = fetchHeight(block.hash());

                                // 4. Process Block + UTXOs
                                processBlockWithUtxo(conn, block, height);

                                // 5. Commit
                                dbIndexStore.DBConn().commit(conn);

                                System.out.println("run() Processed block " + height + " with " + block.transactions().size() + " transactions.");
                            } catch (SQLException e) {
                                try {
                                    if (conn != null) dbIndexStore.DBConn().rollback(conn);
                                } catch (SQLException ex) {}
                            } finally {
                                if (conn != null) try {
                                    dbIndexStore.DBConn().releaseThreadConnection();
                                } catch (SQLException ex) {
                                    System.getLogger(RegtestZmqListener.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                                }
                            }                        

                        } catch (Exception e) {
                            AppLogger.error(className, "run() Listener error onRawBlock: "
                                + e.getMessage());
                        }
                    }
                }
                
                try { 
                    SyncResult initial = runOnce(); 
                    if (initial != null) {
                        AppLogger.info(className, "run() Initial sync complete: height={}, utxos={}, total_sat={}",
                            initial.getBlockHeight(),
                            initial.getTotalUtxos(),
                            initial.getTotalValue());
                    } else {
                        AppLogger.warn(className, "run() Initial sync skipped (already running)");
                    }
                }
                catch (Exception e) {
                    AppLogger.error(className, "run() Listener error onRawBlock: "
                        + e.getMessage());
                }
                
                monitor.markWorkerFree(id);        //waitForSignal(id);
            }
        } catch (InterruptedException ex) {
            System.getLogger(RegtestZmqListener.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
        
    public BlockData parseRawRegtestBlock(byte[] body) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Bitcoin protocol is LE

        // --- PARSE HEADER (First 80 bytes) ---
        int version = buffer.getInt();

        byte[] prevHashBytes = new byte[32];
        buffer.get(prevHashBytes);
        String prevHashHex = bytesToHexReverse(prevHashBytes);

        byte[] merkleBytes = new byte[32];
        buffer.get(merkleBytes);
        String merkleHex = bytesToHexReverse(merkleBytes);

        long timestamp = buffer.getInt() & 0xFFFFFFFFL; // Read as unsigned
        
        long bits = buffer.getInt() & 0xFFFFFFFFL;
        String bitsHex = Long.toHexString(bits);

        long nonce = buffer.getInt() & 0xFFFFFFFFL;

        // --- CALCULATE BLOCK HASH ---
        // The block hash is the double SHA256 of the 80-byte header
        byte[] headerBytes = new byte[80];
        System.arraycopy(body, 0, headerBytes, 0, 80);
        String blockHash = sha256TwiceHex(headerBytes); // Returns reversed hex string

        // --- PARSE TRANSACTIONS ---
        int txCount = (int) readVarInt(buffer);
        List<TxData> transactions = new ArrayList<>(txCount);

        for (int i = 0; i < txCount; i++) {
            // We parse the transaction minimally to get its ID or just skip bytes
            // For this example, we calculate the TxID.
            // Note: In a real high-perf env, you might not want to hash every Tx here.
            TxData tx = parseTransaction(buffer);
            transactions.add(tx);
        }

        // --- HANDLE HEIGHT ---
        // Raw blocks DO NOT contain height. 
        // You must look this up via RPC or maintain a state variable.
        // Example:
        int height = fetchHeight(blockHash); 
        
        // --- CONSTRUCT OBJECT ---
        return new BlockData(
            blockHash,
            height,
            prevHashHex,
            merkleHex,
            timestamp,
            bitsHex,
            (int) nonce,
            version,
            txCount,
            0, // weight
            transactions
        );
    }

    private TxData parseTransaction(ByteBuffer buffer) throws Exception {
        // --- Step 1: Parse Raw Data into Intermediate Structures ---
        buffer.mark(); 
        int startPos = buffer.position();

        int version = buffer.getInt();
        
        // SegWit Detection
        boolean hasWitness = false;
        byte marker = buffer.get();
        if (marker == 0x00) {
            byte flag = buffer.get();
            if (flag == 0x01) hasWitness = true;
            else { buffer.reset(); buffer.position(buffer.position() + 4); }
        } else {
            buffer.position(buffer.position() - 1);
        }

        // Parse Inputs
        long inCount = readVarInt(buffer);
        List<RawTxInput> rawInputs = new ArrayList<>();
        boolean isCoinbase = false;
        
        for (int i = 0; i < inCount; i++) {
            byte[] prevHash = new byte[32]; buffer.get(prevHash);
            int prevIndex = buffer.getInt();
            long scriptLen = readVarInt(buffer);
            byte[] scriptBytes = new byte[(int) scriptLen]; buffer.get(scriptBytes);
            int sequence = buffer.getInt();

            if (isAllZero(prevHash) && prevIndex == 0xFFFFFFFF) {
                isCoinbase = true;
            }
            
            rawInputs.add(new RawTxInput(prevHash, prevIndex, scriptBytes, sequence));
        }

        // Parse Outputs
        long outCount = readVarInt(buffer);
        List<RawTxOutput> rawOutputs = new ArrayList<>();
        long totalOutputValue = 0;

        for (int i = 0; i < outCount; i++) {
            long value = buffer.getLong();
            long scriptLen = readVarInt(buffer);
            byte[] scriptBytes = new byte[(int) scriptLen]; buffer.get(scriptBytes);
            
            totalOutputValue += value;
            rawOutputs.add(new RawTxOutput((int)i, value, scriptBytes));
        }

        // Skip Witness if present (simplified)
        if (hasWitness) {
            for (int i = 0; i < inCount; i++) {
                long count = readVarInt(buffer);
                for (int j = 0; j < count; j++) {
                    long len = readVarInt(buffer);
                    buffer.position(buffer.position() + (int) len);
                }
            }
        }
        int locktime = buffer.getInt();

        // --- Step 2: Calculate TXID and Metadata ---
        int endPos = buffer.position();
        int totalSize = endPos - startPos;
        // Weight calculation skipped for brevity in this snippet, assume vsize=size
        int vsize = totalSize; 
        int weight = totalSize * 4; // Non-witness approx

        byte[] rawBytes = new byte[totalSize];
        buffer.reset(); buffer.get(rawBytes);
        String rawHex = bytesToHex(rawBytes);
        String txid = sha256TwiceHex(rawBytes);

        // --- Step 3: Construct Final Data Objects ---

        // 3a. Construct TxInputData
        List<TxInputData> inputs = new ArrayList<>();
        for (int i = 0; i < rawInputs.size(); i++) {
            RawTxInput raw = rawInputs.get(i);
            
            String prevTxidHex = isCoinbase ? null : bytesToHexReverse(raw.prevHash);
            int prevVout = raw.prevIndex;
            String coinbaseData = isCoinbase ? bytesToHex(raw.script) : null;
            String scriptSig = isCoinbase ? null : bytesToHex(raw.script);
            
            inputs.add(new TxInputData(
                prevTxidHex,
                prevVout,
                coinbaseData,
                scriptSig,
                raw.sequence,
                null // isCoinbase && i == 0
            ));
        }

        // 3b. Construct TxOutputData
        List<TxOutputData> outputs = new ArrayList<>();
        for (RawTxOutput raw : rawOutputs) {
            // Parse Script for Address and Type
            ScriptInfo info = parseScriptTypeAndAddress(raw.script);
            
            // Note: prevTxidHex here refers to the transaction that CREATED this output (current tx)
            outputs.add(new TxOutputData(
                txid,       // The tx containing this output
                raw.index,  // The vout index
                raw.value,  // Satoshis
                bytesToHex(raw.script),
                info.address,
                info.type
            ));
        }

        long feeSat = isCoinbase ? 0 : 0; // Requires UTXO lookup to calculate actual fee

        return new TxData(txid, version, locktime, inputs, outputs, isCoinbase, totalSize, vsize, weight, feeSat, rawHex);
    }

    // --- 3. Helpers & Intermediate Classes ---

    static class RawTxInput {
        byte[] prevHash; int prevIndex; byte[] script; int sequence;
        RawTxInput(byte[] ph, int pi, byte[] s, int seq) { prevHash=ph; prevIndex=pi; script=s; sequence=seq; }
    }
    
    static class RawTxOutput {
        int index; long value; byte[] script;
        RawTxOutput(int i, long v, byte[] s) { index=i; value=v; script=s; }
    }
    
    static class ScriptInfo {
        String type; String address;
        ScriptInfo(String t, String a) { type=t; address=a; }
    }

    // Basic Script Parser (P2PKH, P2SH, P2WPKH, P2WSH)
    private ScriptInfo parseScriptTypeAndAddress(byte[] script) {
        if (script == null || script.length == 0) return new ScriptInfo("NULL", null);
        
        // P2PKH: OP_DUP OP_HASH160 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
        if (script.length == 25 && script[0] == (byte)0x76 && script[1] == (byte)0xA9 && script[2] == 0x14 && script[23] == (byte)0x88 && script[24] == (byte)0xAC) {
            byte[] hash = new byte[20];
            System.arraycopy(script, 3, hash, 0, 20);
            return new ScriptInfo("P2PKH", pubkeyHashToAddress(hash, (byte)0x00)); // 0x00 for Mainnet/Testnet P2PKH prefix
        }
        
        // P2SH: OP_HASH160 <scriptHash> OP_EQUAL
        if (script.length == 23 && script[0] == (byte)0xA9 && script[1] == 0x14 && script[22] == (byte)0x87) {
            byte[] hash = new byte[20];
            System.arraycopy(script, 2, hash, 0, 20);
            return new ScriptInfo("P2SH", pubkeyHashToAddress(hash, (byte)0x05)); // 0x05 for Testnet P2SH
        }

        // P2WPKH: OP_0 <20-byte-pubkey-hash>
        if (script.length == 22 && script[0] == 0x00 && script[1] == 0x14) {
            byte[] hash = new byte[20];
            System.arraycopy(script, 2, hash, 0, 20);
            return new ScriptInfo("P2WPKH", segwitAddress("tb", 0, hash)); // "tb" for testnet bech32
        }

        // P2WSH: OP_0 <32-byte-script-hash>
        if (script.length == 34 && script[0] == 0x00 && script[1] == 0x20) {
            byte[] hash = new byte[32];
            System.arraycopy(script, 2, hash, 0, 32);
            return new ScriptInfo("P2WSH", segwitAddress("tb", 0, hash));
        }

        // OP_RETURN
        if (script[0] == (byte)0x6A) {
            return new ScriptInfo("OP_RETURN", null);
        }

        return new ScriptInfo("UNKNOWN", null);
    }

    // Mock Address Generators (In prod use BitcoinJ or similar)
    private String pubkeyHashToAddress(byte[] hash, byte prefix) {
        return "1" + bytesToHex(hash).substring(0, 10); // Placeholder for Base58Check
    }
    
    private String segwitAddress(String hrp, int witver, byte[] program) {
        return hrp + "1" + bytesToHex(program).substring(0, 10); // Placeholder for Bech32
    }

    // Existing helpers (readVarInt, sha256TwiceHex, etc.)
     private long readVarInt(ByteBuffer buffer) {
        // Read byte as unsigned int (0-255) to avoid signed issues (-128 to 127)
        int first = buffer.get() & 0xFF; 

        return switch (first) {
            case 0xFF -> buffer.getLong();
            case 0xFE -> buffer.getInt() & 0xFFFFFFFFL;
            case 0xFD -> buffer.getShort() & 0xFFFFL;
            default -> first;
        }; // Mask to keep it unsigned
        // Mask to keep it unsigned
        // First byte is the value itself
    }
        
    private boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
    
    private String bytesToHexReverse(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = bytes.length - 1; i >= 0; i--) sb.append(String.format("%02x", bytes[i]));
        return sb.toString();
    }
    
    private String sha256TwiceHex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash1 = digest.digest(bytes);
        byte[] hash2 = digest.digest(hash1);
        StringBuilder sb = new StringBuilder();
        for (int i = hash2.length - 1; i >= 0; i--) sb.append(String.format("%02x", hash2[i]));
        return sb.toString();
    }
      
    /**
     * Fetches the block height from the Bitcoin RPC daemon.
     * Raw blocks received via ZMQ do not contain the height field.
     */
    private int fetchHeight(String blockHash) {
        try {
            // Execute 'getblockheader' RPC command.
            // This is faster than 'getblock' because it doesn't return full transaction data.
            Object response = BitcoinRpcClient.executeRpc("getblockheader", blockHash);

            if (response instanceof JSONObject) {
                JSONObject header = (JSONObject) response;
                
                // Extract height. Handle potential Long/Integer casting issues
                Object heightObj = header.get("height");
                if (heightObj instanceof Number) {
                    return ((Number) heightObj).intValue();
                } else if (heightObj instanceof String) {
                    return Integer.parseInt((String) heightObj);
                }
            }
            
            AppLogger.warn("fetchHeight(...) Height not found in RPC response for hash: {}", blockHash);
            return -1;

        } catch (BitcoinRpcException | NumberFormatException | JSONException e) {
            AppLogger.error(className, "fetchHeight(...) RPC Error fetching height for hash " + blockHash, e);
            // Return -1 or rethrow depending on your error handling strategy
            return -1;
        }
    }
    
    public void processBlockWithUtxo(Connection conn, BlockData block, int height) throws Exception {
            
            dbIndexStore.processBlock(conn, height, block);

            int chainTip = dbIndexStore.getChainTipHeight(conn);

            //int count = 0;
            for (TxData tx : block.transactions()) {

                // 🔻 Mark spent
                if (!tx.isCoinbase()) {
                    for (TxInputData in : tx.inputs()) {
                        dbIndexStore.markUtxoSpent(conn, 
                                in.prevTxId(), in.prevVout(),
                                tx.txid(), height
                        );
                    }
                }                
            
                // 🔺 Create UTXOs
                for (TxOutputData out : tx.outputs()) {

                    long amountSat = out.valueSat();
                    double amountBtc = amountSat / 100_000_000.0;

                    String address   = out.address();
                    String scriptHex = out.scriptHex();

                    int confirmations = (chainTip >= height)
                            ? (chainTip - height + 1)
                            : 0;

                    String descriptor = deriveDescriptor(out);
                    boolean solvable  = deriveSolvable(out);   // ✅ NEW

                    Utxo utxo = new Utxo(
                            tx.txid(),
                            out.vout(),
                            height,
                            tx.isCoinbase(),
                            confirmations,
                            address,
                            scriptHex,
                            BigDecimal.valueOf(amountBtc),
                            amountSat,
                            out.isSpendable(),
                            true,              // safe
                            descriptor,
                            solvable,          // ✅ NEW
                            false,             // spent
                            null,              // spent_by_txid
                            null               // spent_at_height
                    );
                    UtxoSet.getInstance().put(tx.txid() + ":" + out.vout(), utxo);
                    dbIndexStore.updateUtxo(conn, utxo);
                    //count++;
                }
            }  
            
    }
    
    private boolean deriveSolvable(TxOutputData out) {

        String type = out.scriptType();

        return switch (type.toLowerCase()) {
            case "p2pkh",
                 "pubkeyhash",
                 "p2wpkh",
                 "witness_v0_keyhash",
                 "p2sh",
                 "p2tr",
                 "witness_v1_taproot" -> true;

            default -> false; // unknown or non-standard
        };
    }
    
    private String deriveDescriptor(TxOutputData out) {

        String addr = out.address();
        String type = out.scriptType();

        if (addr == null || addr.isEmpty()) {
            return ""; // non-standard
        }

        return switch (type.toLowerCase()) {

            case "witness_v0_keyhash", "p2wpkh" ->
                "wpkh(" + addr + ")";

            case "witness_v1_taproot", "p2tr" ->
                "tr(" + addr + ")";

            case "pubkeyhash", "p2pkh" ->
                "pkh(" + addr + ")";

            case "scripthash", "p2sh" ->
                "sh(" + addr + ")";

            default ->
                "addr(" + addr + ")"; // fallback
        };
    } 
    
    public int getNodeTipHeight() throws IOException, Exception {
        // e.g. call getblockcount via your RPC client
        return walletRpc.getChainTipHeight();
    }
    
    public BlockData fetchBlockViaRpc(int height) throws IOException, BitcoinRpcException {
        // 1. getblockhash(height)
        // 2. getblock(hash, 0 or 2)
        // 3. map to BlockData (same structure used by RawBlockDecoder)
        String hash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);
        JSONObject blockJson = (JSONObject) BitcoinRpcClient.executeRpc("getblock", hash, 2);

        BlockData block = BlockDataMapper.mapBlock(blockJson);  
        return block;
    }
    
    /**
    * Checks whether any blocks have been missed since the last
    * processed height. Used by RegtestZmqListener after a sequence gap.
    *
    * @return number of missed blocks (0 = nothing to catch up)
     * @throws java.lang.Exception
    */
    public int getMissedBlockCount() throws Exception {
       Connection conn = dbIndexStore.DBConn().getConnection();
       try {
           int lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
           int tip        = walletRpc.getCoreTipHeight();
           int missed     = Math.max(0, tip - lastHeight);

           AppLogger.debug(className, 
                   "getMissedBlockCount()] :"
                   + " lastHeight={} tip={} missed={}",
                   lastHeight, tip, missed);

           return missed;
       } finally {
           dbIndexStore.DBConn().releaseThreadConnection();
       }
    }
    
    /**
    * Runs a catch-up sync — processes any blocks missed since last sync.
    * Called by RegtestZmqListener when a sequence gap is detected.
    *
    * Safe to call from any thread — guarded by syncing AtomicBoolean.
    *
    * @return SyncResult or null if sync already in progress
     * @throws java.lang.Exception
    */
    public SyncResult catchUp() throws Exception {
       //if (!catchUpOnlyMode) {
           
       //    AppLogger.warn("[ZmqBlockProcessor] catchUp() called but not in"
       //            + " catch-up-only mode — use performSync() instead.");
       //    return performSync();
       //}
       
       if (!syncing.compareAndSet(false, true)) {
           AppLogger.warn(className, "catchUp() Catch-up already in progress"
                   + " — skipping.");
           return null;
       }

       AppLogger.info(className, "catchUp() ── Catch-up sync starting ────────");

       try {
           Connection conn = dbIndexStore.DBConn().getConnection();
           int lastHeight;
           try {
               lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
           } catch (SQLException e) {
               AppLogger.error(className, "catchUp() Could not read last height: "
                       + e.getMessage());
               throw e;
           } finally {
               dbIndexStore.DBConn().releaseThreadConnection();
           }

           int tip = walletRpc.getCoreTipHeight();
           int gap = (lastHeight < 0)
                   ? Integer.MAX_VALUE
                   : (tip - lastHeight);

           AppLogger.info(className, String.format(
                   "catchUp() Catch-up: lastHeight=%d tip=%d gap=%d",
                   lastHeight, tip, gap));

           if (gap == 0) {
               AppLogger.info(className, "catchUp() Already at tip — nothing"
                       + " to catch up.");
               return buildEmptyResult(tip);
           }

           if (gap > 0) {
               AppLogger.info(className, String.format(
                       "catchUp() Catching up %d block(s) [%d → %d]",
                       gap, lastHeight, tip));
               syncFromCore();
           }

           SyncResult result = syncOnce();

           AppLogger.info(className, String.format(
                   "catchUp() Catch-up complete:"
                   + " height=%d utxos=%d sat=%d updated=%d",
                   result.getBlockHeight(),
                   result.getTotalUtxos(),
                   result.getTotalValue(),
                   result.getUpdatedUtxos()));

           return result;

       } finally {
           syncing.set(false);
       }
    }
    
    public SyncResult runOnce() {

        if (!syncing.compareAndSet(false, true)) {
            AppLogger.warn(className, "runOnce() Sync already in progress, skipping runOnce()");
            return null;
        }

        try {
            return performSync();   // ✅ correct
        } catch (Exception ex) {
            System.getLogger(RegtestZmqListener.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        } finally {
            syncing.set(false);
        }
        return null;
    }
   
    public SyncResult performSync() throws Exception {
        Connection conn = dbIndexStore.DBConn().getConnection();   // thread's own connection
        int lastHeight;
        
        try {
            lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
            AppLogger.info(className, "performSync() Resuming from height={}", lastHeight);
        } catch (SQLException e) {
            AppLogger.error(className, "performSync() Error: {}", e.getMessage());
            throw e;
        }

        int tip = walletRpc.getCoreTipHeight();

        int gap = (lastHeight < 0) ? Integer.MAX_VALUE : (tip - lastHeight);

        AppLogger.info(className, "performSync() Sync decision: lastHeight={}, tip={}, gap={}", lastHeight, tip, gap);

        // 🚀 Decision logic
        if (lastHeight < 0) {
            AppLogger.info(className, "performSync() No previous sync → running full block sync");
            syncFromCore();
            return syncOnce(); // refresh UTXO view after
        }

        if (gap > BLOCK_SYNC_THRESHOLD) {
            AppLogger.info(className, "performSync() Gap {} > threshold {} → running block sync", gap, BLOCK_SYNC_THRESHOLD);
            syncFromCore();
            return syncOnce();
        }
        
        // Normal fast path
        return syncOnce();
    }
   
    public void syncFromCore() throws Exception {
        Connection conn = dbIndexStore.DBConn().getConnection();
        try{
            
        int lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);

        ChainReorgHandler reorgHandler =
            new ChainReorgHandler(dbIndexStore);

        reorgHandler.ensureOnMainChain(conn, lastHeight);

        lastHeight = dbIndexStore.getLastHeightOrMinusOne(conn);
        int tip = walletRpc.getCoreTipHeight();

        AppLogger.info(className, "syncFromCore() Block sync: {} → {}", lastHeight, tip);
        if (lastHeight == tip) {
            AppLogger.info(className, "syncFromCore() Already at tip — skipping sync");
            return;
        }

        dbIndexStore.DBConn().beginTransaction(conn);
        for (int height = lastHeight + 1; height <= tip; height++) {

            String hash = (String) BitcoinRpcClient.executeRpc("getblockhash", height);
            JSONObject blockJson =
                (JSONObject) BitcoinRpcClient.executeRpc("getblock", hash, 2);

            BlockData block = BlockDataMapper.mapBlock(blockJson);  
            
            //processLock.lock();
            //try {
            // ✅ process block + UTXO together
            processBlockWithUtxo(conn, block, height);
            //} finally {
            //    processLock.unlock();
            //}
            if (height % 100 == 0) {
                AppLogger.info(className, "syncFromCore() Processed block {}", height);
            }
        }
        AppLogger.info(className, "syncFromCore() Block sync complete up to height {}", tip);
        dbIndexStore.DBConn().commit(conn);
        } catch (SQLException e) {
            dbIndexStore.DBConn().rollback(conn);
            throw e;
        } finally {
            // ALWAYS release — runs whether we return, throw, or complete normally
            dbIndexStore.DBConn().releaseThreadConnection();
        }
        //AppLogger.info(className, "Block sync complete up to height {}", tip);
    }
   
   public SyncResult syncOnce() throws Exception {
        long startTime = System.currentTimeMillis();
        SyncResult result = new SyncResult();

        AppLogger.info(className, "syncOnce() Starting UTXO sync...");

        // 1) Get current block height
        Object blockchainInfo = walletRpc.executeRpc("getblockchaininfo");
        if (!(blockchainInfo instanceof JSONObject blkInfo)) {
            throw new RuntimeException("syncOnce() getblockchaininfo: unexpected type " + blockchainInfo.getClass());
        }

        long currentHeight = blkInfo.getLong("blocks");
        result.setBlockHeight(currentHeight);

        // 2) Optional: rescan logic (if you implement import+rescan)
        if (lastSyncHeight > 0 && currentHeight > lastSyncHeight) {
            long blocksToScan = currentHeight - lastSyncHeight;
            AppLogger.info(className, String.format("syncOnce() Blocks advanced by %d (from %d to %d)",
                    blocksToScan, lastSyncHeight, currentHeight));
        } else if (lastSyncHeight == 0) {
            AppLogger.info(className, "syncOnce() First sync - expecting empty or initial UTXO set.");
        }

        // 3) Get UTXOs from watch-only wallet
        List<Utxo> rpcUtxos = walletRpc.listUnspent(0, 9999999);
        CachedWalletMnemMap.cachedNewObject("rpcUtxos", rpcUtxos);  
      
        int tipHeight = walletRpc.getCoreTipHeight();
        
        long totalValue;
        int numOfNewUtxos = 0;
        //Connection conn = dbIndexStore.DBConn();
        //conn.setAutoCommit(false);
        Connection conn = dbIndexStore.DBConn().getConnection();
        try{
            dbIndexStore.DBConn().beginTransaction(conn);
        for (Utxo utxo : rpcUtxos) {  
            int confirmations = utxo.getConfirmations();

            int height = (confirmations > 0)
                    ? tipHeight - confirmations + 1
                    : -1; // unconfirmed
            
            dbIndexStore.updateUtxo(conn, utxo);             
        }       
                
        UtxoSummary  utxoSummary = UtxoAnalyzer.analyze(rpcUtxos);
        System.out.println("\n############################### Utxo Summary ###################################");
        System.out.println(utxoSummary.toString());
        System.out.println("##################################################################################");
        result.setTotalUtxos(rpcUtxos.size());

        totalValue = rpcUtxos.stream()
            .mapToLong(u -> u.getAmount()
                    .multiply(BigDecimal.valueOf(100_000_000L))
                    .longValueExact())
            .sum();
        
        result.setTotalValue(totalValue);
        
        boolean isNew;
        //int numOfNewUtxos = 0;
        for (Utxo u : rpcUtxos) {

            isNew = !dbIndexStore.containsUtxo(conn, u);
            
            dbIndexStore.updateUtxo(conn, u); 
                
            if (isNew) {
                onUtxoReceived(u.getAddress());
                numOfNewUtxos++;
            }
        }  
        
        dbIndexStore.DBConn().commit(conn);
        } catch (Exception e) {
            // rollback any open transaction — safe even if none is open
            dbIndexStore.DBConn().rollback(conn);
            AppLogger.error(className, "syncOnce() syncFromCore failed: {}", e.getMessage());
            throw e;

        } finally {
            // ALWAYS release — runs whether we return, throw, or complete normally
            dbIndexStore.DBConn().releaseThreadConnection();
        }
             
        result.setUpdatedUtxos(numOfNewUtxos);
       
        lastSyncHeight = currentHeight;
        lastSyncTime = System.currentTimeMillis();

        long duration = lastSyncTime - startTime;
        result.setDurationMs(duration);

        AppLogger.info(className, String.format(
            "syncOnce() UTXO sync completed in %d ms: %d UTXOs, %d sat total, %d updated",
            duration, rpcUtxos.size(), totalValue, numOfNewUtxos));

        return result;
    }
   
   public void onUtxoReceived(String address) throws Exception {

        AppLogger.info(className, "onUtxoReceived(...) Received funds on {}", address);

        addressManager.markUsed(address);

        // refresh cache if needed
        addressCache.add(address);

        // refill pool
        addressManager.ensureLookahead();
    }   
   
   private SyncResult buildEmptyResult(int height) {
       SyncResult result = new SyncResult();
       result.setBlockHeight(height);
       result.setTotalUtxos(0);
       result.setTotalValue(0);
       result.setUpdatedUtxos(0);
       result.setDurationMs(0);
       return result;
   }
   
   // Simple POJO to return stats
    public static final class SyncResult {
        private long blockHeight;
        private int totalUtxos;
        private long totalValue;
        private int updatedUtxos;
        private long durationMs;

        public long getBlockHeight() { return blockHeight; }
        public void setBlockHeight(long h) { this.blockHeight = h; }

        public int getTotalUtxos() { return totalUtxos; }
        public void setTotalUtxos(int n) { this.totalUtxos = n; }

        public long getTotalValue() { return totalValue; }
        public void setTotalValue(long v) { this.totalValue = v; }

        public int getUpdatedUtxos() { return updatedUtxos; }
        public void setUpdatedUtxos(int n) { this.updatedUtxos = n; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long d) { this.durationMs = d; }
    }
}
