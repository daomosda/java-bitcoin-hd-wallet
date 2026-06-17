/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.bitcoin.hdwallet.cacheUtil;

/**
 *
 * @author user
 */

import com.bitcoin.hdwallet.core.AppLogger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CachedObjSet {
    private Set<Object> objectSet;

    public CachedObjSet() {
        objectSet = Collections.synchronizedSet(new HashSet<>());
    }

    public void copyObjectSet(Set<Object> objectes) {
        if (objectes == null) {
            this.objectSet = Collections.synchronizedSet(new HashSet<>());
        } else {
            this.objectSet = Collections.synchronizedSet(new HashSet<>(objectes));
        }
    }

    public Set<Object> getObjectSet() {
        return new HashSet<>(this.objectSet); // Defensive copy
    }

    public void addObject(Object obj) {
        if (obj == null) return;

        synchronized (objectSet) {
            if (!objectSet.contains(obj)) {
                objectSet.add(obj);
                AppLogger.info("[CachedSet => objectAdded()] obj ({}) added", (String) obj);
            }
            AppLogger.info("[CachedSet => objectAdded()] obj ({}) not added", (String) obj);
        }
    }

    public Object removeObject(Object obj) {
        if (obj == null || objectSet== null) {
            return null;
        }

        synchronized (objectSet) {
            if (objectSet.remove(obj)) {
                AppLogger.info("[CachedSet => removeObject()] obj ({}) removed", (String) obj);
                return obj;
            }
            AppLogger.info("[CachedSet => removeObject()] obj ({}) not removed", (String) obj);
        }
        return null;
    }
}