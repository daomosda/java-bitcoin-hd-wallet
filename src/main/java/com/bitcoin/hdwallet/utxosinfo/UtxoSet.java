package com.bitcoin.hdwallet.utxosinfo;

import com.bitcoin.hdwallet.model.Utxo;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author CONALDES
 */

public final class UtxoSet {

    private static final UtxoSet INSTANCE = new UtxoSet();

    // Key is "txid:vout"
    private final ConcurrentHashMap<String, Utxo> utxos = new ConcurrentHashMap<>();
    
    private UtxoSet() {}

    public static UtxoSet getInstance() {        
        return INSTANCE;
    }
    
    public Set<Map.Entry<String, Utxo>> entrySet() {
        return utxos.entrySet();
    }
    public Map<String, Utxo> getUtxos() {
        return utxos;
    }
    
    private String key(String txid, long vout) {
        return txid + ":" + vout;
    }

    public Utxo lookup(String prevTxId, long outputIndex) {
        if (prevTxId == null) return null;
        return utxos.get(key(prevTxId, outputIndex));
    }

    public int getSize() {
        return utxos.size();
    }

    /** Get UTXO by canonical id "txid:vout".
     * @param utxoId
     * @return  */
    public Utxo get(String utxoId) {
        return utxos.get(utxoId);
    }

    /** Add or update a UTXO.
     * @param utxoId
     * @param utxo
     * @param out */
    public void put(String utxoId, Utxo utxo) {
        if ((utxo != null) && !this.utxos.containsKey(utxoId)) {
            utxos.put(utxoId, utxo);
        }        
    }

    /** Remove a UTXO completely.
     * @param utxoId */
    public void remove(String utxoId) {
        utxos.remove(utxoId);
    }
    
    public void clear() {
        utxos.clear();
    }

    /**
     * Mark a UTXO as spent.
     * @param utxoId canonical form "txid:vout"
     */
    public void markSpent(String utxoId) {
        Utxo utxo = utxos.get(utxoId);
        if (utxo == null) {
            // Already spent / not tracked – you can choose to ignore or throw
            // throw new IllegalStateException("UTXO not found: " + utxoId);
            return;
        }
        //utxo.setSpent(true);       // assumes you have setSpent(boolean)
        utxos.remove(utxoId);     // keep set strictly “unspent only”
    }
}
