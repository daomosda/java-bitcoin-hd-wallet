/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.lightningnetwork;

/**
 *
 * @author DAOMOSDA
 */

public final class IdGenerator {

    private static long id = 0;

    public static synchronized long next() {
        return ++id;
    }
}