package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author CONALDES
 */

public final class IdGenerator {

    private static long id = 0;

    public static synchronized long next() {
        return ++id;
    }
}