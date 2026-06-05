package com.bitcoin.hdwallet.repository;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.chaindata.StoredBlock;
import com.bitcoin.hdwallet.chaindata.TxData;
import com.bitcoin.hdwallet.utxosinfo.UtxoData;
import com.bitcoin.hdwallet.chaindata.TxInputData;
import com.bitcoin.hdwallet.chaindata.BlockData;
import com.bitcoin.hdwallet.chaindata.TxOutputData;
import com.bitcoin.hdwallet.crypto.HexUtils;
import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.CachedWalletMnemMap;
import com.bitcoin.hdwallet.database.ChainDatabase;
import com.bitcoin.hdwallet.model.Utxo;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * All DB read/write operations for the five chain-index tables.
 * Every method participates in an outer transaction when called
 * from ChainIndexer.applyBlock().
 */
public class ChainIndexRepository {

    private final ChainDatabase sqLteDb;
    
    private static final int BATCH_SIZE = 100;
    private static final int RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 100;
    
    public ChainIndexRepository(ChainDatabase sqLteDb) throws SQLException {
        this.sqLteDb = sqLteDb;
    }
    
    public ChainDatabase DBConn() throws SQLException  {
        return this.sqLteDb;
    } 
      
    public int getLocalTipHeight(Connection conn) throws SQLException {

        String sql = "SELECT COALESCE(MAX(height), -1) FROM blocks";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            return rs.next() ? rs.getInt(1) : -1;
        }
    }
    
    public String getLocalTipHash(Connection conn) throws Exception {

        String sql = "SELECT hash FROM blocks WHERE id = 1";

        Map<String, Object> rs = sqLteDb.queryOne(conn, sql); // ✅ NO params

        return (rs == null) ? null : (String) rs.get("hash");
    }

    public void setChainTip(Connection conn, String hash, int height) throws Exception {

        String sql = """
            INSERT INTO blocks (id, hash, height)
            VALUES (1, ?, ?)
            ON CONFLICT(id)
            DO UPDATE SET hash = excluded.hash,
                          height = excluded.height
            """;

        sqLteDb.execute(conn, sql, hash, height); // ✅ varargs (no array)
    }
    // ── blocks ───────────────────────────────────────────────────────

    public void insertBlock(Connection conn, BlockData b) throws Exception {
        String sql = """
            INSERT OR IGNORE INTO blocks
              (height, hash, prev_hash, merkle_root, timestamp,
               bits, nonce, version, tx_count, size_bytes, weight)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """;
        Object[] params = {
            b.height(),
            b.hash(),
            b.prevHash() != null ? b.prevHash() : "",
            b.merkleRoot(),
            b.timestamp(),
            b.bits(),
            b.nonce(),
            b.version(),
            b.transactions().size(),
            b.size(),
            b.weight()};
        
        sqLteDb.execute(conn, sql, params);
    }

    // ── transactions ─────────────────────────────────────────────────

    public void insertTransaction(Connection conn, TxData tx, int blockHeight, String blockHash,
                                   int txIndex, boolean coinbase, long feeSat)
            throws Exception {
        String sql = """
            INSERT OR IGNORE INTO transactions
              (txid, block_height, block_hash, tx_index, version, locktime,
               is_coinbase, input_count, output_count, fee_sat,
               size_bytes, vsize_bytes, weight, raw_hex)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        Object[] params = {
            tx.txid(),
            blockHeight,
            blockHash,
            txIndex,
            tx.version(),
            tx.locktime(),
            coinbase,
            tx.inputs().size(),
            tx.outputs().size(),
            feeSat,
            tx.size(),
            tx.vsize(),
            tx.weight(),
            tx.rawHex()};
        
        sqLteDb.execute(conn, sql, params);
    }
    
    // ── ChainIndexRepository.java — getStoredTip implementation ───────────────

    /**
     * Returns the highest block we have stored in the DB, or null if
     * the blocks table is empty (first run / fresh DB).
     *
     * SQL:
     *   SELECT height, hash, prev_hash, merkle_root, timestamp,
     *          bits, nonce, version, tx_count, size_bytes, weight, synced_at
     *   FROM   blocks
     *   ORDER  BY height DESC
     *   LIMIT  1
     *
     * Used by:
     *   • ChainSyncer      — to know where to resume syncing from
     *   • ReorgDetector    — to compare our tip hash against Core's tip hash
     *   • BitcoinWalletApp — to confirm sync status and pass currentHeight
     *                        to balance / UTXO queries
     * @param conn
     * @return 
     * @throws java.sql.SQLException
     */
    public StoredBlock getStoredTip(Connection conn) throws SQLException {
        String sql =
            "SELECT height, hash, prev_hash, merkle_root, timestamp, " +
            "       bits, nonce, version, tx_count, size_bytes, " +
            "       weight, synced_at " +
            "FROM   blocks " +
            "ORDER  BY height DESC " +
            "LIMIT  1";
        
        
            try (
                PreparedStatement ps = conn.prepareStatement(sql); 
                    ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    AppLogger.warn("[ChainIndexStore] getStoredTip: " +
                            "blocks table is empty — returning null");
                    return null;
                }
                StoredBlock tip = new StoredBlock(
                        rs.getInt   ("height"),
                        rs.getString("hash"),
                        rs.getString("prev_hash"),
                        rs.getString("merkle_root"),
                        rs.getLong  ("timestamp"),
                        rs.getString("bits"),
                        rs.getLong  ("nonce"),
                        rs.getInt   ("version"),
                        rs.getInt   ("tx_count"),
                        rs.getInt   ("size_bytes"),
                        rs.getInt   ("weight"),
                        rs.getLong  ("synced_at")
                );
                AppLogger.info("[ChainIndexStore] getStoredTip → {}",
                        tip.label());
                return tip;
            }
    }

    public List<String> getTxidsAtHeight(Connection conn, int height) throws Exception {
        String sql = "SELECT txid FROM transactions WHERE block_height = ?";
        Object[] params = {height};
        //Connection conn = sqLteDb.getConnection();
        List<Map<String, Object>> rs = sqLteDb.queryList(conn, sql, params);
        return (List<String>) rs.get(0);
        //return sqLteDb.queryList(sql, ps -> ps.setInt(1, height), rs -> rs.getString("txid"));
    }

    public List<String> getTxidsAboveHeight(Connection conn, int height) throws Exception {
        String sql = "SELECT txid FROM transactions WHERE block_height > ?";
        Object[] params = {height};
        //Connection conn = sqLteDb.getConnection();
        List<Map<String, Object>> rs = sqLteDb.queryList(conn, sql, params);
        return (List<String>) rs.get(0);
    }

    public void deleteTransactionsAboveHeight(Connection conn, int height) throws Exception {
        Object[] params = {height};
        //Connection conn = sqLteDb.getConnection();
        sqLteDb.executeUpdate(conn, "DELETE FROM transactions WHERE block_height > ?", params);
        //    ps -> ps.setInt(1, height));
    }

    // ── transaction_inputs ───────────────────────────────────────────
    
    public void insertInput(Connection conn, String txid, int index, TxInputData vin) throws Exception {
        String sql =
            "INSERT OR IGNORE INTO transaction_inputs " +
            "  (txid, input_index, prev_txid, prev_vout, " +
            "   script_sig_hex, sequence, witness_hex) " +
            "VALUES (?,?,?,?,?,?,?)";

        // Build witness JSON string
        String witnessJson = null;
        if (vin.witness() != null && !vin.witness().isEmpty()) {
            witnessJson = "[\"" + String.join("\",\"", vin.witness()) + "\"]";
        }

        // coinbase inputs have no prev outpoint — use null for those slots
        Object prevTxid  = vin.isCoinbase() ? null : vin.prevTxId();
        Object prevVout  = vin.isCoinbase() ? null : vin.prevVout();

        // coinbase: store raw coinbase hex in script_sig_hex
        // spend:    store the actual scriptSig hex (empty string for segwit)
        String scriptSigHex = vin.isCoinbase()
            ? vin.coinbaseData()           
            : vin.scriptSigHex();

        Object[] params = {
            txid,
            index,
            prevTxid,
            prevVout,
            scriptSigHex,
            vin.sequence(),
            witnessJson
        };

        sqLteDb.execute(conn, sql, params);
    }

    // ── transaction_outputs ──────────────────────────────────────────

    public void insertOutput(Connection conn, String txid, TxOutputData vout) throws Exception {
        String sql = """
            INSERT OR IGNORE INTO transaction_outputs
              (txid, vout, value_sat, script_hex, script_type, address)
            VALUES (?,?,?,?,?,?)
            """;
        Object[] params = {
            txid,
            vout.vout(),
            btcToSats(BigDecimal.valueOf(vout.valueSat())),
            vout.scriptHex(),
            vout.scriptType(),
            vout.address()
        };

        sqLteDb.execute(conn, sql, params);
    }

    /** Used by ChainIndexer to calculate fees.
     * @param txid
     * @param vout
     * @return 
     * @throws java.lang.Exception */
    public Long getOutputValueSat(Connection conn, String txid, int vout) throws Exception {
        String sql = "SELECT value_sat FROM transaction_outputs WHERE txid=? AND vout=?";
        Object[] params = {txid, vout};
        //Connection conn = sqLteDb.getConnection();
        Map<String, Object> rs = sqLteDb.queryOne(conn, sql, params);
        return (Long) rs.get("value_sat");
    }

    // ── utxos ────────────────────────────────────────────────────────

    public void insertUtxo(Connection conn, String txid, TxOutputData vout,
                            int height, boolean coinbase) throws Exception {
        String sql = """
            INSERT OR IGNORE INTO utxos
              (txid, vout, value_sat, address, script_hex, script_type,
               created_height, coinbase, spent)
            VALUES (?,?,?,?,?,?,?,?,0)
            """;
        Object[] params = {
            txid,
            vout.vout(),
            btcToSats(BigDecimal.valueOf(vout.valueSat())),
            vout.address(),
            vout.scriptHex(),
            vout.scriptType(),
            height,
            coinbase
        };
    }
    
     public void insertUtxo(Connection conn, UtxoData utxoData) throws Exception {
        String sql = """
            INSERT OR IGNORE INTO utxos
              (txid, vout, value_sat, address, script_hex, script_type,
               created_height, coinbase, spent)
            VALUES (?,?,?,?,?,?,?,?,0)
            """;
        Object[] params = {
            utxoData.txid,
            utxoData.vout,
            utxoData.btcToSats,
            utxoData.address,
            utxoData.scriptPubKeyHex,
            utxoData.scriptType,
            utxoData.height,
            utxoData.coinbase
        };

        sqLteDb.execute(conn, sql, params);
    }     
      
    public void markUtxoSpent(Connection conn, String prevTxid, int prevVout,
                               String spenderTxid, int height) throws Exception {
        String sql = """
            UPDATE utxos
            SET spent=1, spent_by_txid=?, spent_at_height=?
            WHERE txid=? AND vout=? AND spent=0
            """;
        Object[] params = {
            spenderTxid,
            height,
            prevTxid,
            prevVout
        };

        sqLteDb.execute(conn, sql, params);
    }
    
    public void unspendAboveHeight(Connection conn, int height) throws SQLException {

        String sql = """
            UPDATE utxos
            SET spent = 0,
                spent_by_txid = NULL,
                spent_height = NULL
            WHERE spent_height > ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, height);
            ps.executeUpdate();
        }
    }

    /** Restore UTXOs spent by orphaned transactions back to unspent.
     * @param conn
     * @param orphanedTxids
     * @throws java.lang.Exception */
    public void unspendUtxosSpentBy(Connection conn, List<String> orphanedTxids) throws Exception {
        if (orphanedTxids.isEmpty()) return;
        String placeholders = String.join(",",
            Collections.nCopies(orphanedTxids.size(), "?"));
        String sql = String.format("""
            UPDATE utxos
            SET spent=0, spent_by_txid=NULL, spent_at_height=NULL
            WHERE spent_by_txid IN (%s)
            """, placeholders);
        
        Object[] params = new Object[orphanedTxids.size()];
        int i = 0;
        for (String orphanedTxid : orphanedTxids) {
            params[i] = orphanedTxid;
            i++;
        }

        sqLteDb.execute(conn, sql, params);
    }

    public void deleteUtxosCreatedAbove(Connection conn, int height) throws Exception {
        Object[] params = {height};
        //Connection conn = sqLteDb.getConnection();
        sqLteDb.executeUpdate(conn, "DELETE FROM utxos WHERE created_height > ?", params);
    }   
    
    public StoredBlock getBlockAtHeight(Connection conn, int height) throws SQLException {
        String sql = """
            SELECT height, hash, prev_hash, merkle_root, timestamp,
                   bits, nonce, version, tx_count, size_bytes, weight, synced_at
            FROM   blocks
            WHERE  height = ?
            """;
            
        Map<String, Object> row = sqLteDb.queryOne(conn, sql, height);
        return row == null ? null : rowToStoredBlock(row);
    }
    
    // ── Reorg support ─────────────────────────────────────────────────

    // ── ChainIndexRepository.java — rollbackToHeight without lambda ───────────
    
    public void standAlonerollbackToHeight(int forkHeight) throws SQLException, Exception {
        Connection conn = sqLteDb.getConnection();
        try {
            sqLteDb.beginTransaction(conn);    //conn.setAutoCommit(false);

            // 1. collect orphaned txids
            List<String> orphanTxids = getTxidsAboveHeight(conn, forkHeight);
            AppLogger.info("[Store] Orphaned txids above height {}: {}",
                forkHeight, orphanTxids.size());

            // 2. restore UTXOs that orphaned txs spent
            unspendUtxosSpentBy(conn, orphanTxids);

            // 3. delete UTXOs created in orphaned blocks
            deleteUtxosCreatedAbove(conn, forkHeight);

            // 4. CASCADE from blocks deletes transactions → inputs/outputs
            deleteBlocksAboveHeight(conn, forkHeight);

            // 5. update tip pointer
            String newTipHash = getBlockHash(conn, forkHeight);
            if (newTipHash != null) {
                setChainTip(conn, newTipHash, forkHeight);
            } else {
                AppLogger.warn("[Store] No block found at forkHeight={} — tip NOT updated",
                    forkHeight);
            }

            sqLteDb.commit(conn);      //conn.commit();
            AppLogger.warn("[Store] Rolled back to height={} ({} orphan txids removed)",
                forkHeight, orphanTxids.size());

        } catch (SQLException e) {
            if (!conn.getAutoCommit()) { // ✅ SAFE GUARD
                sqLteDb.rollback(conn);   //conn.rollback();
            }
            AppLogger.error("[Store] standAlonerollbackToHeight({}) FAILED: {}", forkHeight, e.getMessage());
            throw e;        
        } finally {
            sqLteDb.releaseThreadConnection();
        }        
    }
        
    public void rollbackToHeight(Connection conn, int forkHeight) throws SQLException, Exception {
                    // 1. collect orphaned txids
            List<String> orphanTxids = getTxidsAboveHeight(conn, forkHeight);
            AppLogger.info("[Store] Orphaned txids above height {}: {}",
                forkHeight, orphanTxids.size());

            // 2. restore UTXOs that orphaned txs spent
            unspendUtxosSpentBy(conn, orphanTxids);

            // 3. delete UTXOs created in orphaned blocks
            deleteUtxosCreatedAbove(conn, forkHeight);

            // 4. CASCADE from blocks deletes transactions → inputs/outputs
            deleteBlocksAboveHeight(conn, forkHeight);

            // 5. update tip pointer
            String newTipHash = getBlockHash(conn, forkHeight);
            if (newTipHash != null) {
                setChainTip(conn, newTipHash, forkHeight);
            } else {
                AppLogger.info("[Store] No block found at forkHeight={} — tip NOT updated",
                    forkHeight);
            }

            AppLogger.info("[Store] Rolled back to height={} ({} orphan txids removed)",
                forkHeight, orphanTxids.size());
    }

    /**
     * Deletes orphaned blocks.
     * ON DELETE CASCADE removes transactions → inputs + outputs automatically.
     *
     * SQL:
     *   DELETE FROM blocks WHERE height > forkHeight
     * @param conn
     * @param forkHeight
     * @throws java.sql.SQLException
     */
    public void deleteBlocksAboveHeight(Connection conn, int forkHeight)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM blocks WHERE height > ?")) {
            ps.setInt(1, forkHeight);
            int rows = ps.executeUpdate();
            AppLogger.info("[Store] deleteBlocksAboveHeight({}): deleted {} block(s) " +
                      "(cascade applied)", forkHeight, rows);
        }
    }

    // ── getBlockHash ──────────────────────────────────────────────────────

    /**
     * Returns our stored hash for a given height, or null.
     *
     * SQL:
     *   SELECT hash FROM blocks WHERE height = ?
     */
    public String getBlockHash(Connection conn, int height)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash FROM blocks WHERE height = ?")) {
            ps.setInt(1, height);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("hash") : null;
            }
        }
    }

    // ── Query helpers (wallet / UI) ───────────────────────────────────

    /** All unspent UTXOs for a given address.
     * @param address
     * @return
     * @throws java.sql.SQLException */
    public List<Map<String, Object>> getUnspentUtxos(String address)
        throws SQLException {

        String sql =
            "SELECT u.txid, u.vout, u.value_sat, u.created_height, " +
            "       u.coinbase, u.script_hex, b.timestamp " +
            "FROM   utxos u " +
            "JOIN   blocks b ON b.height = u.created_height " +
            "WHERE  u.address = ? AND u.spent = 0 " +
            "ORDER  BY u.created_height DESC";

        List<Map<String, Object>> results = new ArrayList<>();
       
        try (Connection conn = sqLteDb.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, address);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("txid",           rs.getString ("txid"));
                    row.put("vout",           rs.getInt    ("vout"));
                    row.put("value_sat",      rs.getLong   ("value_sat"));
                    row.put("created_height", rs.getInt    ("created_height"));
                    row.put("coinbase",       rs.getBoolean("coinbase"));
                    row.put("script_hex",     rs.getString ("script_hex"));
                    row.put("timestamp",      rs.getLong   ("timestamp"));
                    results.add(row);
                }
            }
        }        

        return results;
    }

    /** Full transaction detail including inputs and outputs.
     * @param txid
     * @return
     * @throws java.sql.SQLException */
    public Map<String, Object> getTransactionDetail(String txid)
        throws SQLException {

        Connection conn = sqLteDb.getConnection();
        
        try {
            conn.setAutoCommit(false);
            // ── 1. Transaction header ─────────────────────────────────────
            Map<String, Object> tx = queryTxHeader(conn, txid);
            if (tx == null) {
                AppLogger.warn("[ChainIndexStore] getTransactionDetail: txid={} not found", txid);
                return null;
            }

            // ── 2. Inputs ─────────────────────────────────────────────────
            List<Map<String, Object>> inputs = queryTxInputs(conn, txid);

            // ── 3. Outputs ────────────────────────────────────────────────
            List<Map<String, Object>> outputs = queryTxOutputs(conn, txid);

            // ── 4. Assemble ───────────────────────────────────────────────
            tx.put("inputs",  inputs);
            tx.put("outputs", outputs);
            conn.commit();
            return tx;

        } catch (SQLException e) {
            if (!conn.getAutoCommit()) { // ✅ SAFE GUARD
                conn.rollback();
            }
            throw e;        
        } finally {

            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignore) {}

            conn.close(); // ✅ MUST with Hikari
        }
    }

    // ── queryTxHeader ─────────────────────────────────────────────────────

    /**
     * SQL:
     *   SELECT t.*, b.hash as block_hash_val, b.timestamp as block_time
     *   FROM   transactions t
     *   JOIN   blocks b ON b.height = t.block_height
     *   WHERE  t.txid = ?
     */
    private Map<String, Object> queryTxHeader(Connection conn, String txid)
            throws SQLException {

        String sql =
            "SELECT t.txid, t.block_height, t.is_coinbase, " +
            "       t.fee_sat, t.vsize_bytes, " +
            "       b.hash      AS block_hash_val, " +
            "       b.timestamp AS block_time " +
            "FROM   transactions t " +
            "JOIN   blocks b ON b.height = t.block_height " +
            "WHERE  t.txid = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("txid",         rs.getString ("txid"));
                m.put("block_height", rs.getInt    ("block_height"));
                m.put("block_hash",   rs.getString ("block_hash_val"));
                m.put("block_time",   rs.getLong   ("block_time"));
                m.put("is_coinbase",  rs.getBoolean("is_coinbase"));
                m.put("fee_sat",      rs.getLong   ("fee_sat"));
                m.put("vsize",        rs.getInt    ("vsize_bytes"));
                return m;
            }
        }
    }

    // ── queryTxInputs ─────────────────────────────────────────────────────

    /**
     * SQL:
     *   SELECT input_index, prev_txid, prev_vout,
     *          script_sig_hex, sequence, witness_hex
     *   FROM   transaction_inputs
     *   WHERE  txid = ?
     *   ORDER  BY input_index
     */
    private List<Map<String, Object>> queryTxInputs(Connection conn, String txid)
            throws SQLException {

        String sql =
            "SELECT input_index, prev_txid, prev_vout, " +
            "       script_sig_hex, sequence, witness_hex " +
            "FROM   transaction_inputs " +
            "WHERE  txid = ? " +
            "ORDER  BY input_index";

        
        List<Map<String, Object>> inputs = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index",          rs.getInt    ("input_index"));
                    m.put("prev_txid",      rs.getString ("prev_txid"));
                    m.put("prev_vout",      rs.getObject ("prev_vout"));
                    m.put("script_sig_hex", rs.getString ("script_sig_hex"));
                    m.put("sequence",       rs.getLong   ("sequence"));
                    m.put("witness",        rs.getString ("witness_hex"));
                    inputs.add(m);
                }
            }
        }

        return inputs;
    }

    // ── queryTxOutputs ────────────────────────────────────────────────────

    /**
     * SQL:
     *   SELECT o.vout, o.value_sat, o.address, o.script_type,
     *          u.spent, u.spent_by_txid, u.spent_at_height
     *   FROM   transaction_outputs o
     *   LEFT JOIN utxos u ON u.txid = o.txid AND u.vout = o.vout
     *   WHERE  o.txid = ?
     *   ORDER  BY o.vout
     */
    private List<Map<String, Object>> queryTxOutputs(Connection conn, String txid)
            throws SQLException {

        String sql =
            "SELECT o.vout, o.value_sat, o.address, o.script_type, " +
            "       u.spent, u.spent_by_txid, u.spent_at_height " +
            "FROM   transaction_outputs o " +
            "LEFT JOIN utxos u ON u.txid = o.txid AND u.vout = o.vout " +
            "WHERE  o.txid = ? " +
            "ORDER  BY o.vout";

        List<Map<String, Object>> outputs = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("vout",            rs.getInt    ("vout"));
                    m.put("value_sat",       rs.getLong   ("value_sat"));
                    m.put("address",         rs.getString ("address"));
                    m.put("script_type",     rs.getString ("script_type"));
                    m.put("spent",           rs.getBoolean("spent"));
                    m.put("spent_by_txid",   rs.getString ("spent_by_txid"));
                    m.put("spent_at_height", rs.getObject ("spent_at_height"));
                    outputs.add(m);
                }
            }
        }

        return outputs;
    }
    
    /**
    * Builds a SQL IN-clause placeholder string for n parameters.
    *
    * Examples:
    *   buildPlaceholders(1) → "?"
    *   buildPlaceholders(3) → "?,?,?"
    *   buildPlaceholders(5) → "?,?,?,?,?"
    *
    * Usage:
    *   List<String> ids = List.of("a","b","c");
    *   String sql = "DELETE FROM utxos WHERE spent_by_txid IN ("
    *              + buildPlaceholders(ids.size()) + ")";
    *
    *   PreparedStatement ps = conn.prepareStatement(sql);
    *   for (int i = 0; i < ids.size(); i++) {
    *       ps.setString(i + 1, ids.get(i));
    *   }
    *   ps.executeUpdate();
    */
   private static String buildPlaceholders(int n) {
       if (n <= 0) {
           throw new IllegalArgumentException(
               "buildPlaceholders: n must be > 0, got " + n);
       }
       StringBuilder sb = new StringBuilder();
       for (int i = 0; i < n; i++) {
           if (i > 0) sb.append(',');
           sb.append('?');
       }
       return sb.toString();
   }

    // ── Transaction helpers ───────────────────────────────────────────

    private static long btcToSats(BigDecimal btc) {
        if (btc == null) return 0;
        return btc.multiply(BigDecimal.valueOf(100_000_000)).longValue();
    }
    
    private static StoredBlock rowToStoredBlock(Map<String, Object> row) {
        return new StoredBlock(
            toInt  (row.get("height")),
            str    (row.get("hash")),
            str    (row.get("prev_hash")),
            str    (row.get("merkle_root")),
            toLong (row.get("timestamp")),
            str    (row.get("bits")),
            toLong (row.get("nonce")),
            toInt  (row.get("version")),
            toInt  (row.get("tx_count")),
            toInt  (row.get("size_bytes")),
            toInt  (row.get("weight")),
            toLong (row.get("synced_at"))
        );
    }

    private static int    toInt (Object o) { return o == null ? 0 : ((Number) o).intValue();  }
    private static long   toLong(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private static String str   (Object o) { return o == null ? "" : o.toString();             }

    // 1) Get block hash at a given height
    public String getBlockHashAtHeight(int height) throws SQLException {
        String sql = "SELECT hash FROM blocks WHERE height = ?";
        try (Connection conn = sqLteDb.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, height);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("hash");
                }
                return null; // or throw if you prefer
            }
        }
    }
    
    public int getLastHeightOrMinusOne(Connection conn) throws SQLException {
        String sql = "SELECT MAX(height) AS max_height FROM blocks";

        try (PreparedStatement ps = conn.prepareStatement(sql); 
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int h = rs.getInt("max_height");
                // rs.wasNull() is true when MAX() returns SQL NULL
                // which happens when the blocks table is empty
                return rs.wasNull() ? -1 : h;
            }
            return -1;
        }
    }

    // 4) Process a new block at given height
    public void processBlock(Connection conn, int height, BlockData block) throws SQLException, Exception {
        // You already have persistBlock(Block) with Connection inside;
        // here is an inline version using Hikari.
        String insertBlockSql =
            "INSERT INTO blocks(height, hash, prev_hash, merkle_root, timestamp, bits, nonce, version) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertBlockSql)) {
            ps.setInt(1, height);
            ps.setString(2, block.hash());
            ps.setString(3, block.prevHash());            
            ps.setString(4, block.merkleRoot());
            ps.setTimestamp(5, new Timestamp(block.timestamp()));
            ps.setString(6, block.bits());  
            ps.setLong(7, block.nonce());   
            ps.setInt(8, block.version());
            ps.executeUpdate();

            // Insert transactions & outputs (you already have this logic in persistBlock)
            for (TxData tx : block.transactions()) {
                insertTransaction(conn, block, tx);
            }

            //conn.commit();
        } catch (SQLException e) {
            throw e;        
        }
    }

    private void insertTransaction(Connection conn, BlockData block, TxData tx) throws SQLException, Exception {

        List<TxOutputData> outs = tx.outputs();
        List<TxInputData> ins  = tx.inputs();


        long fee = getFee(conn, ins, outs);  // ✅ FIXED

        String insertTxSql = """
            INSERT OR IGNORE INTO transactions(
                txid, block_height, block_hash, version, locktime,
                is_coinbase, input_count, output_count, fee_sat
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement ps = conn.prepareStatement(insertTxSql)) {
            ps.setString(1, tx.txid());
            ps.setInt(2, block.height());
            ps.setString(3, block.hash());
            ps.setInt(4, tx.version());
            ps.setLong(5, tx.locktime());
            ps.setBoolean(6, false);
            ps.setInt(7, ins.size());
            ps.setInt(8, outs.size());
            ps.setLong(9, fee);   // ✅ use computed fee
            ps.executeUpdate();
        }

        // ✅ Now insert outputs (they will be used by future txs)
        for (TxOutputData out : outs) {
            insertOutput(conn, tx, out);
        }

        // ✅ Then mark inputs as spent
        int inputIndex = 0;

        for (TxInputData in : ins) {

            if (!in.isCoinbase()) {
                markUtxoSpent(
                    conn,
                    in.prevTxId(),
                    in.prevVout(),
                    tx.txid(),
                    block.height()
                );
            }

            insertInput(conn, tx, in, inputIndex);

            inputIndex++;
        }
    }

    private void insertOutput(Connection conn, TxData tx, TxOutputData out) throws SQLException {
        String sql =
            "INSERT INTO transaction_outputs(txid, vout, value_sat, script_hex, script_type, address) " +
            "VALUES(?, ?, ?, ?, ?, ?)";
//txid, vout, value_sat, script_hex, script_type, address
        String address = (String) CachedWalletMnemMap.getObject("minerAddress");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tx.txid());
            ps.setString(2, Integer.toString(out.vout()));
            ps.setDouble(3, out.valueSat());            
            ps.setString(4, out.scriptHex());
            ps.setObject(5, out.scriptType());
            ps.setString(6, address);
            ps.executeUpdate();
        }
    } 
    
    private void insertInput(Connection conn, TxData tx, TxInputData in, int inputIndex) throws SQLException {

        String sql = """
            INSERT INTO transaction_inputs(
                txid, input_index, prev_txid, prev_vout,
                script_sig_hex, sequence, witness_hex
            ) VALUES(?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tx.txid());
            ps.setInt(2, inputIndex);

            if (in.isCoinbase()) {
                ps.setNull(3, Types.VARCHAR);
                ps.setNull(4, Types.INTEGER);
                ps.setString(5, in.scriptSigHex());
            } else {
                ps.setString(3, in.prevTxId());
                ps.setInt(4, in.prevVout());
                ps.setString(5, in.scriptSigHex());
            }

            ps.setLong(6, in.sequence());

            // Safe witness handling
            if (in.witness() != null && !in.witness().isEmpty()) {
                String joined = String.join("", in.witness());
                ps.setBytes(7, HexUtils.hexToBytes(joined));
            } else {
                ps.setNull(7, Types.BLOB);
            }

            ps.executeUpdate();
        }
    }

    // 5) Update last height (chain_state table)
    public void updateLastHeight(Connection conn, int height) throws SQLException {
        // chain_state(last_height INTEGER NOT NULL)
        String sql =
            "UPDATE chain_state SET last_height = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, height);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                // If row does not exist yet, insert it
                String insertSql = "INSERT INTO chain_state(last_height) VALUES (?)";
                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    ins.setInt(1, height);
                    ins.executeUpdate();
                }
            }
        }
    }
    
    public int getChainTipHeight(Connection conn) throws SQLException {
        String sql = "SELECT COALESCE(MAX(height), -1) AS tip FROM blocks";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getInt("tip");
            }
            return -1;
        }
    }
    
    public String getHashAtHeight(Connection conn, int height) throws SQLException {
        String sql = "SELECT hash FROM blocks WHERE height = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, height);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("hash");
                }
                return null; // or throw if you prefer strictness
            }
        }
    }

    // Convenience overloads if you like separate names:
    public String getHashAtHeightLocal(Connection conn, int localHeight) throws SQLException {
        return getHashAtHeight(conn, localHeight);
    }
    
    public void deleteBlocksFromHeight(Connection conn, int fromHeight) throws SQLException {
        // Delete inputs referencing transactions in blocks >= fromHeight
        String deleteInputsSql =
            "DELETE FROM transactioninputs WHERE txid IN (" +
            "  SELECT txid FROM transactions WHERE blockhash IN (" +
            "    SELECT hash FROM blocks WHERE height >= ?))";

        // Delete outputs referencing those transactions
        String deleteOutputsSql =
            "DELETE FROM transactionoutputs WHERE txid IN (" +
            "  SELECT txid FROM transactions WHERE blockhash IN (" +
            "    SELECT hash FROM blocks WHERE height >= ?))";

        // Delete transactions in blocks >= fromHeight
        String deleteTxSql =
            "DELETE FROM transactions WHERE blockhash IN (" +
            "  SELECT hash FROM blocks WHERE height >= ?)";

        // Delete blocks themselves
        String deleteBlocksSql =
            "DELETE FROM blocks WHERE height >= ?";

        //Connection conn = sqLteDb.getConnection();
        //conn.setAutoCommit(false);
        try (PreparedStatement ps1 = conn.prepareStatement(deleteInputsSql);
            PreparedStatement ps2 = conn.prepareStatement(deleteOutputsSql);
            PreparedStatement ps3 = conn.prepareStatement(deleteTxSql);
            PreparedStatement ps4 = conn.prepareStatement(deleteBlocksSql)) {

            ps1.setInt(1, fromHeight);
            ps1.executeUpdate();

            ps2.setInt(1, fromHeight);
            ps2.executeUpdate();

            ps3.setInt(1, fromHeight);
            ps3.executeUpdate();

            ps4.setInt(1, fromHeight);
            ps4.executeUpdate();
        }
           
    }   
           
    private long getFee(Connection conn,
                    List<TxInputData> inputs,
                    List<TxOutputData> outputs) throws SQLException {

        // Coinbase tx → fee = 0
        if (inputs.isEmpty() || inputs.get(0).isCoinbase()) {
            return 0L;
        }

        long inputSum = 0L;
        long outputSum = 0L;

        String sql = """
            SELECT value_sat
            FROM transaction_outputs
            WHERE txid = ? AND vout = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            for (TxInputData in : inputs) {
                ps.setString(1, in.prevTxId());
                ps.setInt(2, in.prevVout());

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        inputSum += rs.getLong("value_sat");
                    }
                }
            }
        }

        for (TxOutputData out : outputs) {
            outputSum += out.valueSat();
        }

        return inputSum - outputSum;
    }
    
    /**
     * Update UTXOs in batch with proper transaction handling
     * @param conn
     * @param utxo
     * @param utxos
     * @return 
     * @throws java.sql.SQLException 
     */
    /*
    public int updateUtxosBatch(List<Utxo> utxos) throws SQLException {
        if (utxos == null || utxos.isEmpty()) {
            return 0;
        }

        AtomicInteger updated = new AtomicInteger(0);
        Connection conn = sqLteDb.getConnection();
        try {
            // Disable auto-commit for batch processing
            conn.setAutoCommit(false);
            
            // Prepare statements
            String upsertSql = """
                INSERT INTO utxos (txid, vout, address, script_pubkey, amount_sat, 
                                   confirmations, spendable, solvable, safe, descriptor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (txid, vout) DO UPDATE SET
                    address = excluded.address,
                    script_pubkey = excluded.script_pubkey,
                    amount_sat = excluded.amount_sat,
                    confirmations = excluded.confirmations,
                    spendable = excluded.spendable,
                    solvable = excluded.solvable,
                    safe = excluded.safe,
                    descriptor = excluded.descriptor
                """;

            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                int batchCount = 0;
                
                for (Utxo utxo : utxos) {
                    stmt.setString(1, utxo.getTxid());
                    stmt.setInt(2, utxo.getVout());
                    stmt.setString(3, utxo.getAddress());
                    stmt.setString(4, utxo.getScriptPubHex());
                    stmt.setBigDecimal(5, satoshisFromBtc(utxo.getAmount().toString()));
                    stmt.setInt(6, utxo.getConfirmations());
                    stmt.setBoolean(7, utxo.isSpendable());
                    stmt.setBoolean(8, utxo.isSolvable());
                    stmt.setBoolean(9, utxo.isSafe());
                    stmt.setString(10, utxo.getDescriptor());
                                       
                    stmt.addBatch();
                    batchCount++;
                    
                    // Execute batch at configured size
                    if (batchCount >= BATCH_SIZE) {
                        int[] results = executeBatchWithRetry(stmt);
                        updated.addAndGet(Arrays.stream(results).sum());
                        batchCount = 0;
                    }
                }
                
                // Execute remaining batch
                if (batchCount > 0) {
                    int[] results = executeBatchWithRetry(stmt);
                    updated.addAndGet(Arrays.stream(results).sum());
                }
            }
            
            // Commit transaction
            conn.commit();
            System.out.printf("Committed batch update: {} UTXOs processed, {} updated", 
                        utxos.size(), updated.get());
            
        } catch (SQLException e) {
            try {
                conn.rollback();
                AppLogger.error("Rolled back batch due to error", e);
            } catch (SQLException rollbackEx) {
                AppLogger.error("Failed to rollback transaction", rollbackEx);
            }
            throw new RuntimeException("Batch update failed", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                AppLogger.warn("Failed to restore auto-commit setting", e.getMessage());
            }
        }
        
        return updated.get();
    }
    */
    
    public int updateUtxo(Connection conn, Utxo utxo) throws SQLException {
        if (utxo == null) {
            return 0;
        }

        String upsertSql = """
            INSERT INTO utxos (txid, vout, address, script_pubkey, amount_sat,
                               confirmations, spendable, solvable, safe, descriptor)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (txid, vout) DO UPDATE SET
                address = excluded.address,
                script_pubkey = excluded.script_pubkey,
                amount_sat = excluded.amount_sat,
                confirmations = excluded.confirmations,
                spendable = excluded.spendable,
                solvable = excluded.solvable,
                safe = excluded.safe,
                descriptor = excluded.descriptor
        """;        
       
        try {
            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {

                stmt.setString(1, utxo.getTxid());
                stmt.setInt(2, utxo.getVout());
                stmt.setString(3, utxo.getAddress());
                stmt.setString(4, utxo.getScriptPubHex());

                // 🔴 Correct BTC → satoshi conversion
                long satoshis = utxo.getAmount()
                        .multiply(BigDecimal.valueOf(100_000_000L))
                        .longValueExact();

                stmt.setLong(5, satoshis);

                stmt.setInt(6, utxo.getConfirmations());
                stmt.setBoolean(7, utxo.isSpendable());
                stmt.setBoolean(8, utxo.isSolvable());
                stmt.setBoolean(9, utxo.isSafe());
                stmt.setString(10, utxo.getDescriptor());

                int affected = stmt.executeUpdate();

                return affected;
            }

        } catch (SQLException e) {
            throw new RuntimeException("UTXO update failed", e);

        } 
    }
    
    public boolean containsUtxo(Connection conn, Utxo utxo) throws SQLException {

        String sql = """
            SELECT 1 FROM utxos
            WHERE txid = ? AND vout = ?
            LIMIT 1
        """;

        //Connection conn = sqLteDb.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, utxo.getTxid());
            ps.setInt(2, utxo.getVout());

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }        
    }

    /**
     * Mark UTXOs as spent
     * @param spendingTxid
     * @param spentInputs
     */
    public void markAsSpent(String spendingTxid, List<SpentInput> spentInputs) throws SQLException {
        if (spentInputs == null || spentInputs.isEmpty()) {
            return;
        }
        Connection conn = sqLteDb.getConnection();
        try {
            conn.setAutoCommit(false);
            
            // Insert spent outpoints
            String insertSpentSql = """
                INSERT INTO spent_outpoints (txid, vout, spending_txid, spending_vin, spent_at_time)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(insertSpentSql)) {
                long now = System.currentTimeMillis() / 1000;
                int batchCount = 0;
                
                for (SpentInput input : spentInputs) {
                    stmt.setString(1, input.getTxid());
                    stmt.setInt(2, input.getVout());
                    stmt.setString(3, spendingTxid);
                    stmt.setInt(4, input.getVin());
                    stmt.setLong(5, now);
                    
                    stmt.addBatch();
                    batchCount++;
                    
                    if (batchCount >= BATCH_SIZE) {
                        executeBatchWithRetry(stmt);
                        batchCount = 0;
                    }
                }
                
                if (batchCount > 0) {
                    executeBatchWithRetry(stmt);
                }
            }
            
            // Delete spent UTXOs
            String deleteUtxoSql = "DELETE FROM utxos WHERE (txid, vout) IN (SELECT txid, vout FROM spent_outpoints WHERE spending_txid = ?)";
            try (PreparedStatement stmt = conn.prepareStatement(deleteUtxoSql)) {
                stmt.setString(1, spendingTxid);
                int deleted = stmt.executeUpdate();
                System.out.printf("Deleted {} spent UTXOs for tx {}", deleted, spendingTxid);
            }
            
            conn.commit();
            
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                AppLogger.error("Failed to rollback", rollbackEx);
            }
            throw new RuntimeException("Failed to mark UTXOs as spent", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                AppLogger.warn("Failed to restore auto-commit", e.getMessage());
            }
        }
    }

    /**
     * Get UTXOs for address
     * @param address
     * @return 
     * @throws java.sql.SQLException
     */
    public List<Utxo> getUtxosForAddress(String address) throws SQLException {
        String sql = """
            SELECT txid, vout, height, coinbase, confirmations, address, script_pubkey ,amount,
                   amount_sat, spendable, safe, descriptor, solvable, spent ,spent_by_txid,
                   spent_at_height
            FROM utxos 
            WHERE address = ? AND spendable = true
            ORDER BY amount_sat DESC
            """;
        
        List<Utxo> utxos = new ArrayList<>();
        try (Connection conn = sqLteDb.getConnection(); 
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, address);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                utxos.add(mapResultSetToUtxo(rs));
            }
        }
        return utxos;
    }

    /**
     * Get all spendable UTXOs
     * @param conn
     * @return 
     * @throws java.sql.SQLException     */
    public List<Utxo> getAllSpendableUtxos(Connection conn) throws SQLException {
         
        String sql = """
            SELECT *
            FROM utxos
            WHERE spent       = 0
              AND spendable   = 1
              AND (
                  coinbase = 0                        -- regular UTXO: any confirmations
                  OR confirmations >= 100             -- coinbase: must be mature
              )
            ORDER BY amount_sat DESC
            """;
        
        List<Utxo> utxos = new ArrayList<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                utxos.add(mapResultSetToUtxo(rs));
            }
        }
        return utxos;
    }

    /**
     * Get UTXOs for coin selection
     * @param minAmount
     * @param minConfirmations
     * @return 
     * @throws java.sql.SQLException
     */
    public List<Utxo> getUtxosForSelection(long minAmount, int minConfirmations) throws SQLException {
        String sql = """
            SELECT txid, vout, height, coinbase, confirmations, address, script_pubkey ,amount,
                   amount_sat, spendable, safe, descriptor, solvable, spent ,spent_by_txid,
                   spent_at_height
            FROM utxos 
            WHERE spendable = true 
              AND amount_sat >= ? 
              AND confirmations >= ?
            ORDER BY amount_sat DESC
            """;
        
        List<Utxo> utxos = new ArrayList<>();
        try (Connection conn = sqLteDb.getConnection(); 
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, minAmount);
            stmt.setInt(2, minConfirmations);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                utxos.add(mapResultSetToUtxo(rs));
            }
        }
        return utxos;
    }
    
    public void updateLastSyncedHeight(String walletName, int height) throws SQLException {
        Connection conn = sqLteDb.getConnection();  // your ChainDatabase wrapper
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(
                "INSERT INTO utxo_sync_state (wallet_name, last_synced_height, updated_at) " +
                "VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now')) " +
                "ON CONFLICT(wallet_name) DO UPDATE SET " +
                "  last_synced_height = excluded.last_synced_height, " +
                "  updated_at = excluded.updated_at"
            );
            ps.setString(1, walletName);
            ps.setInt(2, height);
            ps.executeUpdate();
        } finally {
            if (ps != null) ps.close();
            sqLteDb.releaseThreadConnection();  // or conn.close(), depending on your pool
        }
    }    
    
    public int getLastSyncedHeightOrMinusOne() throws SQLException {
        Connection conn = sqLteDb.getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(
                "SELECT last_synced_height FROM utxo_sync_state WHERE id = 1"
            );
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
            return -1;
        } finally {
            if (rs != null) rs.close();
            if (ps != null) ps.close();
            sqLteDb.releaseThreadConnection();
        }
    }
    
    /**
     * Execute batch with retry logic
     */
    private int[] executeBatchWithRetry(PreparedStatement stmt) throws SQLException {
        SQLException lastException = null;
        
        for (int attempt = 0; attempt < RETRY_ATTEMPTS; attempt++) {
            try {
                return stmt.executeBatch();
            } catch (SQLException e) {
                lastException = e;
                
                // Check if retryable
                if (!isRetryableError(e)) {
                    throw e;
                }
                
                System.out.printf("Batch execution failed (attempt {}/{}): {}", 
                           attempt + 1, RETRY_ATTEMPTS, e.getMessage());
                
                if (attempt < RETRY_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted during retry", ie);
                    }
                }
            }
        }
        
        throw lastException;
    }

    private boolean isRetryableError(SQLException e) {
        // SQLite busy, lock, etc.
        String sqlState = e.getSQLState();
        return "40001".equals(sqlState) ||  // Serialization failure
               "08006".equals(sqlState) ||  // Connection failure
               "08001".equals(sqlState) ||  // SQL client unable to establish connection
               "HYT00".equals(sqlState) ||  // Timeout
               e.getMessage().contains("database is locked") ||
               e.getMessage().contains("busy");
    }
    
    private BigDecimal satoshisFromBtc(String btc) {
        return new BigDecimal(btc)
                .multiply(BigDecimal.valueOf(100_000_000));
                //.longValue();
    }
    
    private Utxo mapResultSetToUtxo(ResultSet rs) throws SQLException {
        Utxo utxo = new Utxo();
        utxo.setTxid(rs.getString("txid"));
        utxo.setVout(rs.getInt("vout"));
        utxo.setHeight(rs.getInt("height"));
        utxo.setCoinbase(rs.getBoolean("coinbase"));
        utxo.setConfirmations(rs.getInt("confirmations"));
        utxo.setAddress(rs.getString("address"));
        utxo.setScriptPubHex(rs.getString("script_pubkey"));
        utxo.setAmount(rs.getBigDecimal("amount"));
        utxo.setAmountSat(rs.getLong("amount_sat"));        
        utxo.setSpendable(rs.getBoolean("spendable"));        
        utxo.setSafe(rs.getBoolean("safe"));
        utxo.setDescriptor(rs.getString("descriptor"));
        utxo.setSolvable(rs.getBoolean("solvable"));        
        utxo.setSpent(rs.getBoolean("spent"));
        utxo.setSpentByTxid(rs.getString("spent_by_txid"));
        utxo.setSpentAtHeight(rs.getInt("spent_at_height"));
        
        return utxo;
    }
        
    public static class SpentInput {
        private String txid;
        private int vout;
        private int vin;

        public SpentInput(String txid, int vout, int vin) {
            this.txid = txid;
            this.vout = vout;
            this.vin = vin;
        }

        public String getTxid() { return txid; }
        public int getVout() { return vout; }
        public int getVin() { return vin; }
    }
}