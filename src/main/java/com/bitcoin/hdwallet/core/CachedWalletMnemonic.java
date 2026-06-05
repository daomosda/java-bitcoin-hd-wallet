package com.bitcoin.hdwallet.core;

import java.util.HashMap;
import java.util.Map;
/**
 *
 * @author CONALDES
 */

public class CachedWalletMnemonic {
    private Map<String, Object> walletMnemonic;
    
    public CachedWalletMnemonic(){
        walletMnemonic = new HashMap<>();
    }
        
    public void copyObjMap(Map<String, Object> walletMnemonic){
        this.walletMnemonic = walletMnemonic;
    }
    
    //public Map<String, Object> getObjMap(){        
    //    return currentParams;
    //}
    
    public Object getMappedObj(String objKey){        
        return walletMnemonic.get(objKey);
    }

    public void cachedNewObj(String param, Object obj){
        if (obj != null) {           
            walletMnemonic.put(param, obj);
        }
    }
}