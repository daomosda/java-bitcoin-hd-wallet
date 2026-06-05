package com.bitcoin.hdwallet.repository;

/**
 *
 * @author CONALDES
 */

import com.bitcoin.hdwallet.database.HDKeyDatabase;
import com.bitcoin.hdwallet.model.HDKey;
import com.bitcoin.hdwallet.core.AppLogger;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * SQLite-backed implementation of HDKeyRepository.
 * Replaces InMemoryHDKeyRepository — drop-in swap, same interface.
 */
public class HDKeyRepository {
   
    public static final int LOOKAHEAD = 5;   
    
    private final HDKeyDatabase db;

    public HDKeyRepository(HDKeyDatabase db) {
        this.db = db;
    }

    public void save(HDKey key) throws SQLException {

        String sql = """
            INSERT INTO hd_keys
            (key_id, private_key, public_key, address, path, account_xpub, 
            fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = db.getConnection()) {

            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, key.getKeyId());
                ps.setBytes(2, key.getPrivKBytesey());
                ps.setBytes(3, key.getPubKeyBytes());
                ps.setString(4, key.getAddress());
                ps.setString(5, key.getPath());
                
                ps.setString(6, key.getAccountXpub());
                ps.setString(7, key.getFingerprintHex());
                ps.setInt(8, key.getFingerprintValue());
                
                ps.setInt(9, key.getKeyIndex());
                ps.setBoolean(10, key.isChange());
                ps.setBoolean(11, key.isUsed());
                ps.setLong(12, System.currentTimeMillis() / 1000L);
                ps.setLong(13, 0L);

                ps.executeUpdate();
            }

            conn.commit();

        } catch (Exception e) {
            throw new SQLException("Save failed", e);
        }
    }
     
    public Optional<HDKey> hdKeyByAddress(String address)
        throws SQLException, IOException {

        String sql = """
            SELECT key_id, private_key, public_key, address, path, account_xpub, 
                fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at
            FROM   hd_keys
            WHERE  address = ?
            """;
        Connection conn = db.getConnection();  
        List<HDKey> results = new ArrayList<>();

        try (
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, address);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HDKey key = mapRow(rs); // your row mapper
                    results.add(key);
                }
            }
        }

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public Optional<HDKey> hdKeyByFingerprint(String fingerprint)
        throws SQLException, IOException {

        String sql = """
            SELECT key_id, private_key, public_key, address, path, account_xpub, 
                fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at
            FROM   hd_keys
            WHERE fingerprint_hex = ?
            """;
        Connection conn = db.getConnection();  
        List<HDKey> results = new ArrayList<>();

        // Do NOT use "connection" field; ask Hikari for a connection here
        try (
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, fingerprint);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HDKey key = mapRow(rs); // your row mapper
                    results.add(key);
                }
            }
        }

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public HDKey pickMiningKey() throws SQLException, IOException {
        Connection conn = db.getConnection();  
        try {
            conn.setAutoCommit(false);

            // 1. find the lowest-index unused address
            HDKey record = findNextUnusedMiningKey().get();

            if (record == null) {
                conn.commit();
                return null;
            }
            
            record.setUsed(true);
            markAddressUsed(record.getAddress());
            conn.commit();

            return record;

        } catch (SQLException e) {
            conn.rollback();
            AppLogger.error("[AddressRepository] pickAndMark failed: {}",
                e.getMessage());
            throw e;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }  
    
    public HDKey pickChangeKey() throws SQLException, IOException {
        Connection conn = db.getConnection();  
        try {
            conn.setAutoCommit(false);

            // 1. find the lowest-index unused address
            HDKey record = findNextUnusedChangeKey().get();

            if (record == null) {
                conn.commit();
                return null;
            }
            record.setUsed(true);
            markAddressUsed(record.getAddress());
            conn.commit();
            
            return record;

        } catch (SQLException e) {
            conn.rollback();
            AppLogger.error("[AddressRepository] pickAndMark failed: {}",
                e.getMessage());
            throw e;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }      
    
    public synchronized void markAddressUsed(String address) throws IOException, SQLException {
        String sql = "UPDATE hd_keys SET used = 1 WHERE address = ?";
        
        try (Connection conn = db.getConnection();  
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            stmt.setString(1, address);
            int rows = stmt.executeUpdate();

            if (rows == 0) {
                throw new NoSuchElementException("[HDKeyRepository => markAddressUsed()] Key not found in DB: " + address);
            }

            conn.commit();            

        } catch (SQLException e) {
            throw new RuntimeException("[HDKeyRepository => markAddressUsed()] Failed to mark key used: " + address, e);        
        }
    }    

    public Optional<HDKey> findNextUnusedMiningKey() throws SQLException {
        String sql = """
            SELECT key_id, private_key, public_key, address, path, account_xpub, 
                fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at
            FROM  hd_keys
            WHERE is_change = 0 AND used = 0
            ORDER BY key_index ASC
            LIMIT  1
            """;

        List<HDKey> results = executeQuery(sql);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public Optional<HDKey> findNextUnusedChangeKey() throws SQLException {
        String sql = """
            SELECT key_id, private_key, public_key, address, path, account_xpub, 
                fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at 
            FROM  hd_keys
            WHERE is_change = 1 AND used = 0
            ORDER BY key_index ASC
            LIMIT  1
            """;

        List<HDKey> results = executeQuery(sql);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public int getFirstFingerPrintValue() throws SQLException {
        String sql = """
            SELECT fingerprint_value
            FROM  hd_keys
            ORDER BY key_index ASC
            LIMIT  1
            """;
        int fingerPrintValue = 0;
        try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet         rs = ps.executeQuery()) {
           while (rs.next()) {
               fingerPrintValue = rs.getInt("fingerprint_value");
           }
        }
       
        return fingerPrintValue;
    }
    
    /**
    * Returns all change addresses that have been marked as used.
    * These are addresses where we sent change but may now have spendable balance.
     * @param conn
     * @return 
     * @throws java.sql.SQLException 
    */
   public List<String> getUsedChangeAddresses()
           throws SQLException {

       String sql = """
               SELECT address
               FROM hd_keys
               WHERE is_change = 1
                 AND used   = 1
               ORDER BY key_index ASC
               """;

       List<String> result = new ArrayList<>();
       try (Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet         rs = ps.executeQuery()) {
           while (rs.next()) {
               result.add(rs.getString("address"));
           }
       }
       
       return result;
    }
    
    // In HDKeyRepository
    public List<String> getAllChangeAddresses() throws SQLException {
        String sql = """
                SELECT address FROM hd_keys
                WHERE is_change = 1
                ORDER BY key_index ASC
                """;
        List<String> result = new ArrayList<>();
        try (Connection        conn = db.getConnection();
             PreparedStatement ps   = conn.prepareStatement(sql);
             ResultSet         rs   = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString("address"));
            }
        }
        return result;
    }

    public int countUnusedMiningKeys() throws SQLException {
        String sql = "SELECT COUNT(*) FROM hd_keys WHERE is_change = 0 AND used = 0";
        
        try (Connection conn = db.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs           = stmt.executeQuery()) {

            return rs.next() ? rs.getInt(1) : 0;

        } catch (SQLException e) {
            throw new RuntimeException("[HDKeyRepository => countUnusedKeys()] Failed to count unused keys", e);
        }
    } 
    
    public int countKeyRecs() throws SQLException {
        String sql = "SELECT COUNT(*) FROM hd_keys";
        
        try (Connection conn = db.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs           = stmt.executeQuery()) {

            return rs.next() ? rs.getInt(1) : 0;

        } catch (SQLException e) {
            throw new RuntimeException("[HDKeyRepository => countUnusedKeys()] Failed to count unused keys", e);
        }
    } 
        
    public Optional<String> pathByAddress(String address)
        throws SQLException, IOException {

        String sql = """
            SELECT path
            FROM   hd_keys
            WHERE address = ?
            """;
        Connection conn = db.getConnection();  

        String retrievedPath = "";
        // Do NOT use "connection" field; ask Hikari for a connection here
        try (
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, address);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    retrievedPath= rs.getString("path");
                }
            }
        }

        return retrievedPath.isEmpty() ? Optional.empty() : Optional.of(retrievedPath);
    }
    
    public int countUnusedChangeKeys() throws SQLException {
        String sql = "SELECT COUNT(*) FROM hd_keys WHERE is_change = 1 AND used = 0";
        
        try (Connection conn = db.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs           = stmt.executeQuery()) {

            return rs.next() ? rs.getInt(1) : 0;

        } catch (SQLException e) {
            throw new RuntimeException("[HDKeyRepository => countUnusedKeys()] Failed to count unused keys", e);
        }
    }
    
    public List<HDKey> findAllUsedKeys() throws SQLException {
        String sql = """
            SELECT key_id, private_key, public_key, address, path, account_xpub, 
                fingerprint_hex, fingerprint_value, key_index, is_change, used, created_at, used_at
            FROM  hd_keys
            WHERE used = 1
            ORDER BY key_index ASC
            """;

        return executeQuery(sql);
    }
    
    public List<String> findAllAddresses() {
        String sql = """
            SELECT address
            FROM   hd_keys
            ORDER BY key_index ASC
            """;

        List<String> addresses = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                // We only want the address column
                String address = rs.getString("address");  // or rs.getString(1) [web:384][web:389]
                addresses.add(address);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                "[HDKeyRepository => executeAddressQuery()] Query failed: " + sql, e);
        }

        return addresses;
    }
    
    public List<String> findMiningAddresses() {
        String sql = """
            SELECT address
            FROM   hd_keys 
            WHERE is_change = 0        
            ORDER BY key_index ASC
            """;

        List<String> addresses = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                // We only want the address column
                String address = rs.getString("address");  // or rs.getString(1) [web:384][web:389]
                addresses.add(address);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                "[HDKeyRepository => executeAddressQuery()] Query failed: " + sql, e);
        }

        return addresses;
    }
            
    public List<String> findChangeAddresses() {
        String sql = """
            SELECT address FROM hd_keys
            WHERE is_change = 1
            ORDER BY key_index ASC
            """;

        List<String> addresses = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                // We only want the address column
                String address = rs.getString("address");  // or rs.getString(1) [web:384][web:389]
                addresses.add(address);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                "[HDKeyRepository => executeAddressQuery()] Query failed: " + sql, e);
        }

        return addresses;
    }
      
    public int getNextIndex() throws SQLException {

        String sql = "SELECT COALESCE(MAX(key_index), -1) FROM hd_keys";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            long max = rs.next() ? rs.getLong(1) : -1;
            return (int) (max + 1);
        }
    }
    
    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<HDKey> executeQuery(String sql) throws SQLException {
        List<HDKey> keys = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                keys.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new RuntimeException("[HDKeyRepository => executeQuery()] Query failed: " + sql, e);
        }

        return keys;
    }    
        
    private HDKey mapRow(ResultSet rs) throws SQLException {
        String keyId = rs.getString("key_id");
        byte[] privateKey = rs.getBytes("private_key");
        byte[] publicKey = rs.getBytes("public_key");
        String address = rs.getString("address");
        String path = rs.getString("path");
        String accountXpub = rs.getString("account_xpub");
        String fingerprintHex = rs.getString("fingerprint_hex");
        int fingerprintValue = rs.getInt("fingerprint_value");
        int keyIndex = rs.getInt("key_index");
        boolean change = rs.getBoolean("is_change");
        boolean used = rs.getBoolean("used");
        long createdAt = rs.getLong("created_at");
        long usedAt = rs.getLong("used_at");
        return new HDKey(keyId, privateKey, publicKey, address, path, accountXpub,
                fingerprintHex, fingerprintValue,  keyIndex, change, used, createdAt, usedAt);
    }
       
    public long queryLong(Connection conn, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // bind any parameters (none in this call, but method supports them)
            for (int i = 0; i < params.length; i++) {
                Object p   = params[i];
                int    pos = i + 1;
                if      (p == null)            ps.setNull   (pos, Types.NULL);
                else if (p instanceof String string)  ps.setString (pos, string);
                else if (p instanceof Integer integer) ps.setInt    (pos, integer);
                else if (p instanceof Long aLong)    ps.setLong   (pos, aLong);
                else if (p instanceof Boolean aBoolean) ps.setBoolean(pos, aBoolean);
                else if (p instanceof Double aDouble)  ps.setDouble (pos, aDouble);
                else                           ps.setObject (pos, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                // COALESCE guarantees a row is always returned
                // but guard with rs.next() anyway
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        }
    }     
}