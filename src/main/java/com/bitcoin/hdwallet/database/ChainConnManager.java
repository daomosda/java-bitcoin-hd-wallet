package com.bitcoin.hdwallet.database;

/**
 *
 * @author CONALDES
 */

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.sql.*;
import java.util.Properties;

/**
 * Configures SQLite for multi-threaded access without HikariCP.
 *
 * Three strategies, pick one based on your use case:
 *
 *   A. One connection per thread  (WAL + multi-thread mode)  ← recommended
 *   B. Single shared connection   (serialized mode + synchronized)
 *   C. Connection pool (manual)   (WAL + per-thread connections from a pool)
 */
public class ChainConnManager {

    private final String dbPath;

    public ChainConnManager(String dbPath) {
        this.dbPath = dbPath;
    }

    // ─────────────────────────────────────────────────────────────────
    // Strategy A — WAL + one connection per thread (RECOMMENDED)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Creates a new connection configured for WAL mode.
     *
     * Rules for this strategy:
     *   • Each thread creates its own connection via this method.
     *   • Connections are NEVER shared between threads.
     *   • WAL allows one writer + multiple concurrent readers.
     *   • Call connection.close() when the thread is done.
     *
     * SQLite threading: SQLITE_THREADSAFE=2 (multi-thread mode)
     *   — safe because each connection is used by exactly one thread.
     * @return 
     * @throws java.sql.SQLException
     */
    public Connection newConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();

        // ── WAL journal mode — best for concurrent read + write ───────
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);

        // ── synchronous=NORMAL — good durability/performance balance ──
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

        // ── threading mode: multi-thread (not serialized) ─────────────
        // Safe when each connection is used by only one thread.
        config.setOpenMode(SQLiteOpenMode.READWRITE);
        config.setOpenMode(SQLiteOpenMode.CREATE);

        // ── performance pragmas ───────────────────────────────────────
        config.setBusyTimeout(5000);      // wait up to 5s if DB is locked
        config.setCacheSize(-16000);      // 16 MB page cache per connection
        config.setTempStore(SQLiteConfig.TempStore.MEMORY);
        config.setPageSize(4096);

        // ── foreign key enforcement ───────────────────────────────────
        config.enforceForeignKeys(true);

        Properties props = config.toProperties();
        props.setProperty("url", "jdbc:sqlite:" + dbPath);

        Connection conn = DriverManager.getConnection(
            "jdbc:sqlite:" + dbPath, props);

        // set WAL mode via PRAGMA (also set by config but explicit is clearer)
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA cache_size=-16000");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA mmap_size=134217728"); // 128 MB mmap
        }

        return conn;
    }
}