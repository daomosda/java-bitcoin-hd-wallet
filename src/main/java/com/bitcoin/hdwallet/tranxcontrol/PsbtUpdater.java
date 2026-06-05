package com.bitcoin.hdwallet.tranxcontrol;

import com.bitcoin.hdwallet.model.Psbt;
import java.io.IOException;

/**
 *
 * @author CONALDES
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
