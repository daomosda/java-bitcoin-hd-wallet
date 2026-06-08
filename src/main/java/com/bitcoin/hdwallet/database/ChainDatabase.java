package com.bitcoin.hdwallet.database;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.AppLogger;
import java.sql.*;
import java.util.*;

/**
 * Database access layer using SQLite without HikariCP.
 *
 * Threading strategy: one connection per thread via ThreadLocal.
 *
 * Every thread that calls getConnection() gets its own dedicated
 * SQLite connection. Connections are created lazily on first use
 * and reused for the lifetime of the thread.
 *
 * This is the correct way to use SQLite with multiple threads:
 *   - WAL mode allows concurrent readers
 *   - Each writer serializes through SQLite's internal locking
 *   - No connection sharing between threads
 */
public class ChainDatabase {

    private final String dbPath;
    private final ChainConnManager connectionManager;

    // one connection per thread — created lazily
    private final ThreadLocal<Connection> threadLocalConnection =
        ThreadLocal.withInitial(() -> null);

    // track all connections for shutdown
    private final Set<Connection> allConnections =
        Collections.synchronizedSet(new HashSet<>());

    public ChainDatabase(String dbPath) {
        this.dbPath            = dbPath;
        this.connectionManager = new ChainConnManager(dbPath);
        AppLogger.info("[Database] Initialized with path: {}", dbPath);
    }

    // ─────────────────────────────────────────────────────────────────
    // Connection management
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns the calling thread's dedicated connection.
     * Creates a new one on first call from this thread.
     *
     * NEVER share the returned connection with another thread.
     * @return 
     * @throws java.sql.SQLException
     */
    public Connection getConnection() throws SQLException {
        Connection conn = threadLocalConnection.get();

        if (conn == null || conn.isClosed()) {
            conn = connectionManager.newConnection();
            threadLocalConnection.set(conn);
            allConnections.add(conn);
            AppLogger.info("[Database] New connection created for thread={}",
                Thread.currentThread().getName());
        }

        return conn;
    }

    /**
     * Releases the current thread's connection back to nothing.
     * The connection is CLOSED — call this when the thread is done
     * (e.g. at the end of a request handler or task).
     *
     * After calling this, the next getConnection() call from this
     * thread creates a fresh connection.
     */
    public void releaseThreadConnection() {
        Connection conn = threadLocalConnection.get();
        if (conn != null) {
            allConnections.remove(conn);
            try { conn.close(); } catch (SQLException e) {
                AppLogger.warn("[Database] Error closing thread connection: {}",
                    e.getMessage());
            }
            threadLocalConnection.remove();
            AppLogger.info("[Database] Connection released for thread={}",
                Thread.currentThread().getName());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Transaction management
    // ─────────────────────────────────────────────────────────────────

    public void beginTransaction(Connection conn) throws SQLException {
        if (!conn.getAutoCommit()) {
            // already in a transaction — do not nest
            AppLogger.warn("[Database] beginTransaction: already in transaction, skipping");
            return;
        }
        conn.setAutoCommit(false);
    }

    public void commit(Connection conn) throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);   // ← restore auto-commit after every commit
    }

    public void rollback(Connection conn) {
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
            conn.setAutoCommit(true);   // ← always restore, even if rollback skipped
        } catch (SQLException e) {
            AppLogger.error("[Database] Rollback failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // DML helpers
    // ─────────────────────────────────────────────────────────────────

    public void execute(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            ps.executeUpdate();
        }
    }

    public int executeUpdate(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Query helpers
    // ─────────────────────────────────────────────────────────────────

    public Map<String, Object> queryOne(Connection conn,
                                         String sql,
                                         Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toMap(rs);
            }
        }
    }

    public List<Map<String, Object>> queryList(Connection conn,
                                                String sql,
                                                Object... params)
            throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(toMap(rs));
            }
        }
        return list;
    }

    public long queryLong(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public String queryString(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public List<String> queryStringList(Connection conn,
                                         String sql,
                                         Object... params)
            throws SQLException {
        List<String>      list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString(1));
            }
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────
    // Schema migrations
    // ─────────────────────────────────────────────────────────────────

    public void runMigrations() throws SQLException {
        Connection conn = getConnection();
        try {
            beginTransaction(conn);
            try (Statement st = conn.createStatement()) {
                for (String sql : SCHEMA.split(";")) {
                    String s = sql.strip();
                    if (!s.isEmpty()) st.executeUpdate(s);
                }
            }
            commit(conn);
            AppLogger.info("[Database] Migrations complete");
        } catch (SQLException e) {
            rollback(conn);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────

    /**
     * Closes ALL connections from ALL threads.
     * Call this once on application shutdown.
     */
    public void shutdown() {
        synchronized (allConnections) {
            for (Connection conn : allConnections) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
            allConnections.clear();
        }
        AppLogger.info("[Database] All connections closed");
    }

    // ─────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────

    private static void bindParams(PreparedStatement ps, Object[] params)
            throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p   = params[i];
            int    pos = i + 1;
            if      (p == null)            ps.setNull   (pos, Types.NULL);
            else if (p instanceof String)  ps.setString (pos, (String)  p);
            else if (p instanceof Integer) ps.setInt    (pos, (Integer) p);
            else if (p instanceof Long)    ps.setLong   (pos, (Long)    p);
            else if (p instanceof Boolean) ps.setBoolean(pos, (Boolean) p);
            else if (p instanceof Double)  ps.setDouble (pos, (Double)  p);
            else                           ps.setObject (pos, p);
        }
    }

    private static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData   meta = rs.getMetaData();
        int                 cols = meta.getColumnCount();
        Map<String, Object> map  = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
            map.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return map;
    }

     // ─────────────────────────────────────────────────────────────────
    // Schema
    // ─────────────────────────────────────────────────────────────────

    private static final String SCHEMA =
        "CREATE TABLE IF NOT EXISTS blocks (" +
        "    height      INTEGER PRIMARY KEY," +
        "    hash        TEXT    NOT NULL UNIQUE," +
        "    prev_hash   TEXT    NOT NULL DEFAULT ''," +
        "    merkle_root TEXT    NOT NULL DEFAULT ''," +
        "    timestamp   INTEGER NOT NULL DEFAULT 0," +
        "    bits        TEXT    NOT NULL DEFAULT ''," +
        "    nonce       INTEGER NOT NULL DEFAULT 0," +
        "    version     INTEGER NOT NULL DEFAULT 1," +
        "    tx_count    INTEGER NOT NULL DEFAULT 0," +
        "    size_bytes  INTEGER NOT NULL DEFAULT 0," +
        "    weight      INTEGER NOT NULL DEFAULT 0," +
        "    synced_at   INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
        ");" +

        "CREATE INDEX IF NOT EXISTS idx_blocks_hash " +
        "    ON blocks(hash);" +

        "CREATE INDEX IF NOT EXISTS idx_blocks_prev_hash " +
        "    ON blocks(prev_hash);" +

        "CREATE TABLE IF NOT EXISTS transactions (" +
        "    txid         TEXT    PRIMARY KEY," +
        "    block_height INTEGER NOT NULL REFERENCES blocks(height) ON DELETE CASCADE," +
        "    block_hash   TEXT    NOT NULL DEFAULT ''," +
        "    tx_index     INTEGER NOT NULL DEFAULT 0," +
        "    version      INTEGER NOT NULL DEFAULT 1," +
        "    locktime     INTEGER NOT NULL DEFAULT 0," +
        "    is_coinbase  INTEGER NOT NULL DEFAULT 0," +
        "    input_count  INTEGER NOT NULL DEFAULT 0," +
        "    output_count INTEGER NOT NULL DEFAULT 0," +
        "    fee_sat      INTEGER NOT NULL DEFAULT 0," +
        "    size_bytes   INTEGER NOT NULL DEFAULT 0," +
        "    vsize_bytes  INTEGER NOT NULL DEFAULT 0," +
        "    weight       INTEGER NOT NULL DEFAULT 0," +
        "    raw_hex      TEXT" +
        ");" +

        "CREATE INDEX IF NOT EXISTS idx_tx_block_height " +
        "    ON transactions(block_height);" +

        "CREATE TABLE IF NOT EXISTS transaction_inputs (" +
        "    id             INTEGER PRIMARY KEY AUTOINCREMENT," +
        "    txid           TEXT    NOT NULL REFERENCES transactions(txid) ON DELETE CASCADE," +
        "    input_index    INTEGER NOT NULL," +
        "    prev_txid      TEXT," +
        "    prev_vout      INTEGER," +
        "    script_sig_hex TEXT," +
        "    sequence       INTEGER NOT NULL DEFAULT 4294967295," +
        "    witness_hex    TEXT," +
        "    UNIQUE (txid, input_index)" +
        ");" +

        "CREATE INDEX IF NOT EXISTS idx_txin_txid " +
        "    ON transaction_inputs(txid);" +

        "CREATE INDEX IF NOT EXISTS idx_txin_prev_txid " +
        "    ON transaction_inputs(prev_txid);" +

        "CREATE TABLE IF NOT EXISTS transaction_outputs (" +
        "    txid        TEXT    NOT NULL REFERENCES transactions(txid) ON DELETE CASCADE," +
        "    vout        INTEGER NOT NULL," +
        "    value_sat   INTEGER NOT NULL DEFAULT 0," +
        "    script_hex  TEXT," +
        "    script_type TEXT," +
        "    address     TEXT," +
        "    PRIMARY KEY (txid, vout)" +
        ");" +

        "CREATE INDEX IF NOT EXISTS idx_txout_address " +
        "    ON transaction_outputs(address);" +
    
        "CREATE TABLE IF NOT EXISTS utxos (" +
        "    txid TEXT NOT NULL," +
        "    vout INTEGER NOT NULL," +
        "    height INTEGER," +  
        "    coinbase BOOLEAN NOT NULL DEFAULT 0," +
        "    confirmations INTEGER NOT NULL," +
        "    address TEXT," +
        "    script_pubkey TEXT," +
        "    amount LONG," +
        "    amount_sat INTEGER NOT NULL," +        
        "    spendable BOOLEAN NOT NULL," +        
        "    safe BOOLEAN NOT NULL," +
        "    descriptor TEXT," +
        "    block_hash TEXT," +
        "    block_height INTEGER," +
        "    solvable BOOLEAN NOT NULL," +
        "    spent BOOLEAN NOT NULL DEFAULT 0," +
        "    spent_by_txid TEXT," +
        "    spent_at_height INTEGER," +
        "    PRIMARY KEY (txid, vout)" +
        ");" +
       
        "CREATE INDEX IF NOT EXISTS idx_utxo_address " +
        "    ON utxos(address);" +
        "CREATE INDEX IF NOT EXISTS idx_utxos_amount " +
        "    ON utxos(amount_sat);" +
        "CREATE INDEX IF NOT EXISTS idx_utxos_confirmations " +
        "    ON utxos(confirmations);" +        

        "CREATE INDEX IF NOT EXISTS idx_utxo_block_height " +
        "    ON utxos(block_height);" +
            
        "CREATE TABLE IF NOT EXISTS spent_outpoints (" +
        "    txid TEXT NOT NULL," +
        "    vout INTEGER NOT NULL," +
        "    spending_txid TEXT NOT NULL," +
        "    spending_vin INTEGER NOT NULL," +
        "    spent_at_block INTEGER," +
        "    spent_at_time INTEGER," +
        "    PRIMARY KEY (txid, vout)" +
        ");" +

        "CREATE TABLE IF NOT EXISTS chain_tip (" +
        "    id     INTEGER PRIMARY KEY DEFAULT 1," +
        "    hash   TEXT    NOT NULL," +
        "    height INTEGER NOT NULL" +
        ");" +
            
        "CREATE TABLE IF NOT EXISTS utxo_sync_state (" +
        "    wallet_name TEXT PRIMARY KEY," +
        "    last_synced_height INTEGER NOT NULL," +
        "    updated_at TEXT NOT NULL" +
        ");" +

        "CREATE TABLE IF NOT EXISTS sync_state (" +
        "    key   TEXT PRIMARY KEY," +
        "    value TEXT NOT NULL" +
        ")";
}
