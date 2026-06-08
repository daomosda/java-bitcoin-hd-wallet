package com.bitcoin.hdwallet.database;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import java.sql.*;

/**
 * Manages the SQLite connection and schema creation.
 */

import java.io.File;

public class HDKeyDatabase {

    private final String dbPath;

    public HDKeyDatabase() {
        File dir = new File(System.getProperty("user.home"), ".bitcoinlike");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        this.dbPath = ConfigFilePaths.hdKeyDBPath();   //new File(dir, "nodeMKeys.db").getAbsolutePath();
        System.out.println("DB path: " + dbPath);

        init(); // create schema once
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private void init() {
        String sql = """
            CREATE TABLE IF NOT EXISTS hd_keys (
                key_id       TEXT    PRIMARY KEY,
                private_key  BLOB    NOT NULL,
                public_key   BLOB    NOT NULL,
                address      TEXT    NOT NULL UNIQUE,
                path         TEXT    NOT NULL,
                account_xpub  TEXT    NOT NULL,   
                fingerprint_hex  TEXT  NOT NULL,
                fingerprint_value  INTEGER NOT NULL,
                key_index    INTEGER NOT NULL,
                is_change    BOOLEAN NOT NULL DEFAULT false,
                used         BOOLEAN NOT NULL DEFAULT false,
                created_at   INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                used_at      INTEGER
            );

            CREATE INDEX IF NOT EXISTS idx_used_created
            ON hd_keys (used, created_at);
            """;       

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize DB", e);
        }
    }
}