package com.bitcoin.hdwallet.cacheUtil;


import java.util.Set;

/**
 *
 * @author user
 */
public class CachedSetObject {
    
    private final static CachedObjSet cachedSet;
    static {
        cachedSet = new CachedObjSet();
    }
    
    public static void copyObjectList(Set<Object> objSet){ 
        cachedSet.copyObjectSet(objSet);
    }
       
    public static void cacheObject(Object obj){
        cachedSet.addObject(obj);
    }
    
    public static Object getCachedOject(Object obj){
        return cachedSet.removeObject(obj);
    }
    
    public static Set<Object> getObjectSet(){        
        return cachedSet.getObjectSet();
    }
}
