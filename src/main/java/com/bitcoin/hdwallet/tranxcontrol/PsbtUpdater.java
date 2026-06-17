/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.tranxcontrol;

import com.bitcoin.hdwallet.model.Psbt;
import java.io.IOException;

/**
 *
 * @author DAOMOSDA
 */

public class PsbtUpdater {

    private final Psbt psbt;

    public PsbtUpdater(Psbt psbt) {
        this.psbt = psbt;
    }

    public void addPartialSignature(
            int inputIndex,
            byte[] pubKey,
            byte[] signatureWithHashType
    ) {

        Psbt.PsbtInput input =
                psbt.getInputs().get(inputIndex);

        input.addPartialSig(
                pubKey,
                signatureWithHashType
        );
    }

    public String toBase64() throws IOException {

        return psbt.toBase64();
    }
}
