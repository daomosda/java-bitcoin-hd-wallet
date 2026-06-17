/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.trezorhardware;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.core.AppLogger;
import com.bitcoin.hdwallet.core.BitcoinRpcClient;
import com.bitcoin.hdwallet.core.BitcoinRpcException;
import com.bitcoin.hdwallet.model.Psbt;
import com.bitcoin.hdwallet.model.Psbt.PsbtInput;
import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Trezor hardware wallet signer — direct USB/HID, no Python dependency.
 *
 * Wire protocol (Trezor common transport):
 *   Each HID report is 64 bytes.
 *   First message chunk: '?' '#' '#' <msg_type:u16 BE> <msg_len:u32 BE> <payload...>
 *   Continuation chunks:  '?' <payload...>
 *   Payload is a length-prefixed Protobuf-encoded message.
 *
 * This class handles framing/chunking. Message bodies are built/parsed
 * via minimal hand-rolled protobuf varint/field encoding for the
 * specific messages needed: GetAddress, GetPublicKey, SignTx (PSBT-style
 * via SignTransaction for legacy or txack flow), ButtonAck, PinMatrixAck.
 */
public final class TrezorSigner implements HardwareSigner {
    
    private static String className = "TrezorSigner";

    // Trezor USB identifiers
    private static final int TREZOR_VID        = 0x1209;
    private static final int TREZOR_ONE_PID    = 0x53C0;
    private static final int TREZOR_MODEL_T_PID = 0x53C1;

    private static final int REPORT_SIZE = 64;

    // Trezor MessageType enum values actually used (from messages.proto)
    private static final int MSG_INITIALIZE       = 0;
    private static final int MSG_FEATURES         = 17;
    private static final int MSG_GET_PUBLIC_KEY    = 11;
    private static final int MSG_PUBLIC_KEY        = 12;
    private static final int MSG_GET_ADDRESS       = 29;
    private static final int MSG_ADDRESS           = 30;
    private static final int MSG_BUTTON_REQUEST    = 26;
    private static final int MSG_BUTTON_ACK        = 27;
    private static final int MSG_PIN_MATRIX_REQUEST = 18;
    private static final int MSG_PIN_MATRIX_ACK    = 19;
    private static final int MSG_FAILURE           = 3;
    private static final int MSG_SIGN_TX           = 15;
    private static final int MSG_TX_REQUEST        = 21;
    private static final int MSG_TX_ACK            = 22;
    
    private final BitcoinRpcClient walletRpc; // ✅ added

    private final HidServices hidServices;
    private HidDevice device;
    private String    masterFingerprintCache;
    private List<HardwareDeviceInfo> devices;
    
    public TrezorSigner(BitcoinRpcClient walletRpc) {
        this.walletRpc = walletRpc;
        HidServicesSpecification spec = new HidServicesSpecification();
        spec.setAutoStart(false);
        this.hidServices = HidManager.getHidServices(spec);
        this.hidServices.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISCOVERY / CONNECTION
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public List<HardwareDeviceInfo> listDevices() {
        List<HardwareDeviceInfo> found = new ArrayList<>();
        for (HidDevice d : hidServices.getAttachedHidDevices()) {
            if (d.getVendorId() == TREZOR_VID
                    && (d.getProductId() == TREZOR_ONE_PID
                        || d.getProductId() == TREZOR_MODEL_T_PID)) {
                String model = d.getProductId() == TREZOR_MODEL_T_PID
                        ? "Model T" : "Model One";
                found.add(new HardwareDeviceInfo(
                        "Trezor", model,
                        d.getSerialNumber(),
                        d.getVendorId(), d.getProductId(),
                        d.getPath()));
                AppLogger.info(className, 
                        " Found device: {} serial={} path={}",
                        model, d.getSerialNumber(), d.getPath());
            }
        }
        if (found.isEmpty()) {
            AppLogger.warn(className, " No Trezor device found on USB.");
        }
        return found;
    }

    @Override
    public boolean isConnected() {
        return device != null && device.isOpen();
    }

    @Override
    public void connect() throws HardwareWalletException {
        devices = listDevices();
        if (devices.isEmpty()) {
            throw new HardwareWalletException(
                    HardwareWalletException.Reason.DEVICE_NOT_FOUND,
                    "No Trezor device detected. Check USB connection.");
        }

        HardwareDeviceInfo info = devices.get(0);
        device = hidServices.getHidDevice(
                info.vendorId(), info.productId(), info.serialNumber());

        if (device == null || !device.isOpen()) {
            throw new HardwareWalletException(
                    HardwareWalletException.Reason.COMMUNICATION_ERROR,
                    "Failed to open HID connection to "
                            + info.model() + " (serial=" + info.serialNumber() + ")");
        }

        AppLogger.info(className, " Connected: " + info.model());

        // Initialize session — required first message on every connect
        sendMessage(MSG_INITIALIZE, new byte[0]);
        TrezorWireMessage resp = receiveMessage();

        if (resp.msgType() != MSG_FEATURES) {
            throw new HardwareWalletException(
                    HardwareWalletException.Reason.COMMUNICATION_ERROR,
                    "Unexpected response to Initialize: type=" + resp.msgType());
        }

        AppLogger.info(className, " Session initialized. Features received ("
                + resp.payload().length + " bytes).");
    }
    
    @Override
    public void disconnect() {
        if (device != null) {
            device.close();
            AppLogger.info(className, " Disconnected.");
        }
        device = null;
        masterFingerprintCache = null;
    }

    public void shutdown() {
        disconnect();
        hidServices.shutdown();
    }

    @Override
    public String getDeviceName() {
        return device != null ? "Trezor (connected)" : "Trezor (not connected)";
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET PUBLIC KEY / FINGERPRINT
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public String getMasterFingerprint() throws HardwareWalletException {
        if (masterFingerprintCache != null) return masterFingerprintCache;

        ensureConnected();

        // GetPublicKey at path m/ (empty path = master)
        byte[] payload = ProtoEncoder.encodeGetPublicKey(
                new int[0], false /* show_display */, "Bitcoin");

        sendMessage(MSG_GET_PUBLIC_KEY, payload);
        TrezorWireMessage resp = awaitFinalResponse();

        if (resp.msgType() != MSG_PUBLIC_KEY) {
            handleUnexpected(resp, "GetPublicKey(master)");
        }

        ProtoDecoder.PublicKeyResult pk = ProtoDecoder.decodePublicKey(resp.payload());
        masterFingerprintCache = pk.fingerprintHex();

        AppLogger.info(className, " Master fingerprint: "
                + masterFingerprintCache);
        return masterFingerprintCache;
    }

    @Override
    public String getXpub(String derivationPath) throws HardwareWalletException {
        ensureConnected();

        int[] pathInts = BipPathParser.parse(derivationPath);

        AppLogger.info(className, " Requesting xpub at path: "
                + derivationPath);

        byte[] payload = ProtoEncoder.encodeGetPublicKey(
                pathInts, false, "Bitcoin");

        sendMessage(MSG_GET_PUBLIC_KEY, payload);
        TrezorWireMessage resp = awaitFinalResponse();

        if (resp.msgType() != MSG_PUBLIC_KEY) {
            handleUnexpected(resp, "GetPublicKey(" + derivationPath + ")");
        }

        ProtoDecoder.PublicKeyResult pk = ProtoDecoder.decodePublicKey(resp.payload());

        AppLogger.info(className, " xpub received for " + derivationPath
                + ": " + pk.xpub().substring(0, 12) + "...");
        return pk.xpub();
    }

    @Override
    public String getAddress(String derivationPath, boolean display)
            throws HardwareWalletException {
        ensureConnected();

        int[] pathInts = BipPathParser.parse(derivationPath);

        AppLogger.info(className, String.format(
                " GetAddress path=%s display=%s",
                derivationPath, display));

        // script_type=3 = SPENDWITNESS (P2WPKH/BIP84)
        byte[] payload = ProtoEncoder.encodeGetAddress(
                pathInts, "Bitcoin", display, 3);

        sendMessage(MSG_GET_ADDRESS, payload);
        TrezorWireMessage resp = awaitFinalResponse();

        if (resp.msgType() != MSG_ADDRESS) {
            handleUnexpected(resp, "GetAddress(" + derivationPath + ")");
        }

        String address = ProtoDecoder.decodeAddress(resp.payload());
        AppLogger.info(className, " Address: " + address);
        return address;
    }

    // ─────────────────────────────────────────────────────────────────────
    // SIGN PSBT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Signs a PSBT on-device.
     *
     * Trezor's wire protocol does not consume raw PSBT bytes directly —
     * it uses a SignTx/TxAck request-response dance where Trezor asks
     * for each input/output one at a time. This method:
     *   1. Parses the PSBT (your existing Psbt class)
     *   2. Verifies bip32_derivs fingerprints match this device
     *   3. Drives the SignTx/TxAck protocol loop
     *   4. Injects returned signatures back into the PSBT
     *
     * The user must physically confirm amounts/addresses on the device
     * screen — ButtonRequest messages are handled transparently here,
     * but in a real UI you would surface "Check your device" to the user.
     * @throws com.bitcoin.hdwallet.trezorhardware.HardwareWalletException
     */
    @Override
    public String signPsbt(String psbtBase64) throws HardwareWalletException {
        try {
            ensureConnected();
            
            AppLogger.info(className, " Starting on-device PSBT signing...");
            
            Psbt psbt;
            JSONObject decoded;
            try {
                psbt = Psbt.parseBase64(psbtBase64);
                // ✅ decoded JSON is the source of truth for bip32_derivs —
                // matches what OwnSigner and TxAckBuilder already use
                decoded = (JSONObject) walletRpc.executeRpc("decodepsbt", psbtBase64);
            } catch (BitcoinRpcException e) {
                throw new HardwareWalletException(
                        HardwareWalletException.Reason.COMMUNICATION_ERROR,
                        "Could not parse/decode PSBT: " + e.getMessage(), e);
            }
            
            String ourFingerprint = getMasterFingerprint();
            
            // ── Verify this device owns at least one input ─────────────────────
            JSONArray decodedInputs = decoded.getJSONArray("inputs");
            boolean ownsAnyInput = false;
            
            for (int i = 0; i < decodedInputs.length(); i++) {
                JSONObject inputMeta = decodedInputs.getJSONObject(i);
                List<Bip32Deriv> derivs = Bip32Deriv.fromInputJson(inputMeta);
                for (Bip32Deriv d : derivs) {
                    if (ourFingerprint.equalsIgnoreCase(d.masterFingerprint())) {
                        ownsAnyInput = true;
                        break;
                    }
                }
                if (ownsAnyInput) break;
            }
            
            if (!ownsAnyInput) {
                throw new HardwareWalletException(
                        HardwareWalletException.Reason.WRONG_FINGERPRINT,
                        "None of the PSBT inputs match this device's fingerprint ("
                                + ourFingerprint + "). Wrong device or wrong wallet.");
            }
            
            // ── Build SignTx request ────────────────────────────────────────────
            int inputCount  = decodedInputs.length();
            int outputCount = decoded.getJSONArray("outputs").length();
            
            AppLogger.info(className,
                    "[TrezorSigner] SignTx: inputs={} outputs={} — "
                            + "CONFIRM ON DEVICE SCREEN", inputCount, outputCount);
            
            byte[] signTxPayload = ProtoEncoder.encodeSignTx(
                    inputCount, outputCount, "Bitcoin");
            sendMessage(MSG_SIGN_TX, signTxPayload);
            
            // ── TxRequest/TxAck loop ─────────────────────────────────────────────
            List<byte[]> collectedSignatures = new ArrayList<>(inputCount);
            TrezorWireMessage resp = awaitFinalResponse();
            
            int loopGuard = 0;
            while (resp.msgType() == MSG_TX_REQUEST && loopGuard++ < 10_000) {
                
                ProtoDecoder.TxRequestResult txReq =
                        ProtoDecoder.decodeTxRequest(resp.payload());
                
                if (txReq.signature() != null) {
                    collectedSignatures.add(txReq.signature());
                    AppLogger.debug(className, " Signature received for input "
                            + (collectedSignatures.size() - 1));
                }
                
                if (txReq.isFinished()) {
                    AppLogger.info(className, " Signing finished. "
                            + collectedSignatures.size() + " signature(s) collected.");
                    break;
                }
                
                // ✅ pass decoded JSON, not psbt object
                byte[] ackPayload = TxAckBuilder.build(txReq, decoded);
                sendMessage(MSG_TX_ACK, ackPayload);
                resp = awaitFinalResponse();
            }
            
            if (resp.msgType() == MSG_FAILURE) {
                String reason = ProtoDecoder.decodeFailureMessage(resp.payload());
                if (reason.toLowerCase().contains("cancel")) {
                    throw new HardwareWalletException(
                            HardwareWalletException.Reason.USER_CANCELLED,
                            "User cancelled signing on device.");
                }
                throw new HardwareWalletException(
                        HardwareWalletException.Reason.COMMUNICATION_ERROR,
                        "Device returned failure: " + reason);
            }
            
            // ── Inject signatures back into PSBT using PsbtInput.addPartialSignature ──
            for (int i = 0; i < collectedSignatures.size() && i < inputCount; i++) {
                JSONObject inputMeta = decodedInputs.getJSONObject(i);
                List<Bip32Deriv> derivs = Bip32Deriv.fromInputJson(inputMeta);
                
                Bip32Deriv ourDeriv = derivs.stream()
                        .filter(d -> ourFingerprint.equalsIgnoreCase(d.masterFingerprint()))
                        .findFirst()
                        .orElse(null);
                
                if (ourDeriv == null) continue; // not our input — skip
                
                byte[] pubkey      = ourDeriv.pubkeyBytes();
                byte[] sigDer      = collectedSignatures.get(i);
                byte[] sigWithType = appendSighashAll(sigDer);
                
                PsbtInput in = psbt.getInputs().get(i);
                in.addPartialSignature(pubkey, sigWithType); // ✅ existing method, unchanged
                AppLogger.debug(className, " Injected signature for input " + i);
            }
            
            AppLogger.info(className, " ✅ PSBT signed on-device.");
            return psbt.toBase64();
        } catch (IOException ex) {
            System.getLogger(TrezorSigner.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
        
        return null;
    }
  
    private byte[] appendSighashAll(byte[] sigDer) {
        byte[] out = new byte[sigDer.length + 1];
        System.arraycopy(sigDer, 0, out, 0, sigDer.length);
        out[sigDer.length] = 0x01; // SIGHASH_ALL
        return out;
    }

    private void ensureConnected() throws HardwareWalletException {
        if (!isConnected()) {
            throw new HardwareWalletException(
                    HardwareWalletException.Reason.DEVICE_NOT_FOUND,
                    "Device not connected. Call connect() first.");
        }
    }

    /**
     * Handles the interactive prompts (PIN, button press) that can occur
     * between request and final response. Loops until a terminal message
     * type is received.
     */
    private TrezorWireMessage awaitFinalResponse() throws HardwareWalletException {
        TrezorWireMessage resp = receiveMessage();
        int guard = 0;

        while (guard++ < 50) {
            switch (resp.msgType()) {
                case MSG_BUTTON_REQUEST -> {
                    AppLogger.info(className, " ⚠️  CONFIRM ON DEVICE —"
                            + " press the button on your Trezor now.");
                    sendMessage(MSG_BUTTON_ACK, new byte[0]);
                    resp = receiveMessage();
                }
                case MSG_PIN_MATRIX_REQUEST -> {
                    throw new HardwareWalletException(
                            HardwareWalletException.Reason.DEVICE_LOCKED,
                            "Device requires PIN entry. PIN entry UI not"
                            + " yet wired — implement PinMatrixAck flow.");
                }
                case MSG_FAILURE -> {
                    return resp; // let caller interpret failure
                }
                default -> {
                    return resp; // terminal response (PublicKey, Address, TxRequest, etc.)
                }
            }
        }
        throw new HardwareWalletException(
                HardwareWalletException.Reason.TIMEOUT,
                "Too many interactive prompts without resolution.");
    }

    private void handleUnexpected(TrezorWireMessage resp, String context)
            throws HardwareWalletException {
        if (resp.msgType() == MSG_FAILURE) {
            String reason = ProtoDecoder.decodeFailureMessage(resp.payload());
            throw new HardwareWalletException(
                    HardwareWalletException.Reason.COMMUNICATION_ERROR,
                    context + " failed: " + reason);
        }
        throw new HardwareWalletException(
                HardwareWalletException.Reason.COMMUNICATION_ERROR,
                context + " — unexpected response type=" + resp.msgType());
    }

    // ─────────────────────────────────────────────────────────────────────
    // WIRE FRAMING (HID transport, 64-byte reports)
    // ─────────────────────────────────────────────────────────────────────

    private record TrezorWireMessage(int msgType, byte[] payload) {}

    private void sendMessage(int msgType, byte[] payload) throws HardwareWalletException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write('#');
        framed.write('#');
        framed.write((msgType >> 8) & 0xFF);
        framed.write(msgType & 0xFF);
        framed.write((payload.length >> 24) & 0xFF);
        framed.write((payload.length >> 16) & 0xFF);
        framed.write((payload.length >> 8) & 0xFF);
        framed.write(payload.length & 0xFF);
        try { framed.write(payload); } catch (Exception ignored) {}

        byte[] full = framed.toByteArray();
        int offset  = 0;
        boolean first = true;

        while (offset < full.length) {
            byte[] report = new byte[REPORT_SIZE];
            report[0] = '?'; // HID report marker (every chunk, per Trezor spec)
            int chunkDataLen = Math.min(REPORT_SIZE - 1, full.length - offset);
            System.arraycopy(full, offset, report, 1, chunkDataLen);
            offset += chunkDataLen;

            int written = device.write(report, REPORT_SIZE, (byte) 0x00);
            if (written < 0) {
                throw new HardwareWalletException(
                        HardwareWalletException.Reason.COMMUNICATION_ERROR,
                        "USB write failed: " + device.getLastErrorMessage());
            }
            first = false;
        }
    }

    private TrezorWireMessage receiveMessage() throws HardwareWalletException {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        int msgType = -1;
        int expectedLen = -1;

        int guard = 0;
        while (guard++ < 1000) {
            byte[] buf = new byte[REPORT_SIZE];
            int read = device.read(buf, 5000); // 5s timeout per chunk
            if (read < 0) {
                throw new HardwareWalletException(
                        HardwareWalletException.Reason.TIMEOUT,
                        "USB read timed out waiting for device response.");
            }

            int pos = 0;
            if (buf[0] == '?') pos = 1;

            if (msgType == -1) {
                // First chunk must contain '#' '#' header right after marker
                if (buf[pos] == '#' && buf[pos + 1] == '#') {
                    msgType = ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
                    expectedLen = ((buf[pos + 4] & 0xFF) << 24)
                                | ((buf[pos + 5] & 0xFF) << 16)
                                | ((buf[pos + 6] & 0xFF) << 8)
                                | (buf[pos + 7] & 0xFF);
                    payloadBuf.write(buf, pos + 8, REPORT_SIZE - pos - 8);
                } else {
                    continue; // not a valid start frame yet — keep reading
                }
            } else {
                payloadBuf.write(buf, pos, REPORT_SIZE - pos);
            }

            if (payloadBuf.size() >= expectedLen) break;
        }

        byte[] full = payloadBuf.toByteArray();
        byte[] payload = new byte[expectedLen];
        System.arraycopy(full, 0, payload, 0, Math.min(expectedLen, full.length));

        return new TrezorWireMessage(msgType, payload);
    }
}


/*
// In TrezorSigner.signPsbt() — fetch decoded JSON once at the start
JSONObject decoded = (JSONObject) walletRpc.executeRpc("decodepsbt", psbtBase64);

// ... inside the TxRequest/TxAck loop, replace:
byte[] ackPayload = TxAckBuilder.build(txReq, psbt);
// with:
byte[] ackPayload = TxAckBuilder.build(txReq, decoded);
*/