package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import java.util.Map;

 public class CachedWalletMnemMap {
    private static final CachedWalletMnemonic walletMnemonic;
    
    static {
        walletMnemonic = new CachedWalletMnemonic();
    }
    
    public static void cacheObjMap(Map<String, Object> objMap){ 
        walletMnemonic.copyObjMap(objMap);
    }
       
    public static void cachedNewObject(String param, Object obj){
        walletMnemonic.cachedNewObj(param, obj);
    }
    
    public static Object getObject(String objKey){        
        return walletMnemonic.getMappedObj(objKey);
    }
}