/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.tranxcontrol;

import java.math.BigDecimal;

/**
 *
 * @author DAOMOSDA
 */

public class PsbtCreateResult {

    private final String psbt;
    private final BigDecimal fee;
    private final int changepos;

    public PsbtCreateResult(
            String psbt,
            BigDecimal fee,
            int changepos
    ) {
        this.psbt = psbt;
        this.fee = fee;
        this.changepos = changepos;
    }

    public String psbt() {
        return psbt;
    }

    public BigDecimal fee() {
        return fee;
    }

    public int changepos() {
        return changepos;
    }
}