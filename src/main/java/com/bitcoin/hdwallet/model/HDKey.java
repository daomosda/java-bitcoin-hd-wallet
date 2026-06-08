package com.bitcoin.hdwallet.model;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.crypto.Script;
import com.bitcoin.hdwallet.crypto.SegWitAddress;
import com.bitcoin.hdwallet.keymanagement.ECDSASignature;
import com.bitcoin.hdwallet.keymanagement.HDKeySerializer;
import java.math.BigInteger;
import java.security.*;
import java.util.UUID;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class HDKey { 
    
    private final BigInteger privKey;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    
    private static final X9ECParameters CURVE_PARAMS =
            SECNamedCurves.getByName("secp256k1");

    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(
                    CURVE_PARAMS.getCurve(),
                    CURVE_PARAMS.getG(),
                    CURVE_PARAMS.getN(),
                    CURVE_PARAMS.getH()
            );

    private final String  keyId;
    private PublicKey publicKey;
    private PrivateKey privateKey;
    private final byte[] privKeyBytes;
    private final byte[]  pubKeyBytes;
    private final String  address;
    private String  path;
    private int keyIndex;
    private boolean used;
    private long createdAt;
    private long usedAt;
    private boolean change;
    
    private String accountXpub;
    private String fingerprintHex; 
    private int fingerprinValue;
    
    private SegWitAddress.Network net = SegWitAddress.Network.REGTEST;

    public HDKey(byte[] privKeyBytes, byte[] pubKeyBytes, String  path, 
            String accountXpub, String fingerprintHex, int fingerprinValue, int keyIndex, boolean change) {
        this.keyId      = UUID.randomUUID().toString();
        this.privKeyBytes = privKeyBytes;
        this.pubKeyBytes  = pubKeyBytes;
        this.address    = SegWitAddress.fromPubKeyCompressed(pubKeyBytes, "p2wpkh", net);  //SegWitAddress.fromPublicKeyP2WPKH(pubKeyBytes, true);
        this.path       = path;
        
        this.accountXpub  = accountXpub;
        this.fingerprintHex = fingerprintHex;
        this.fingerprinValue  = fingerprinValue;        
        this.keyIndex   = keyIndex;
        this.change       = change;
        this.used       = false;
        this.createdAt = System.currentTimeMillis() / 1000L;
        this.usedAt = 0L;
        this.privKey = new BigInteger(1, this.privKeyBytes);
    }

    // Reconstruct from database row
    public HDKey(String keyId, byte[] privKeyBytes,  byte[] pubKeyBytes, String address,                 
                 String  path, String accountXpub, String fingerprintHex, int fingerprinValue, 
                 int keyIndex, boolean change, boolean used, long createdAt, long usedAt) {
        this.keyId      = keyId;
        this.privKeyBytes = privKeyBytes;
        this.pubKeyBytes  = pubKeyBytes;
        this.address    = address;
        this.path       = path;        
        this.accountXpub  = accountXpub;
        this.fingerprintHex = fingerprintHex;
        this.fingerprinValue  = fingerprinValue;        
        this.keyIndex   = keyIndex;
        this.change       = change;
        this.used       = used;
        this.createdAt = createdAt;
        this.usedAt = usedAt;this.privKey = new BigInteger(1, this.privKeyBytes);
        
    }
    
    //public void setPath(String path) {
    //    this.path = path;
    //}
    
    public String getPath() {
        return this.path;
    }
    
    //public void setKeyIndex(int keyIndex) {
    //    this.keyIndex = keyIndex;
    //}
    
    public int getKeyIndex() {
        return this.keyIndex;
    }

    public byte[] getPrivKBytesey() {
        return privKeyBytes;
    }

    public byte[] getPubKeyBytes() {
        return pubKeyBytes;
    }
    
    public String getAccountXpub() {
        return accountXpub;
    }
    
    public String getFingerprintHex() {
        return fingerprintHex;
    }
    
    public int getFingerprintValue() {
        return fingerprinValue;
    }
    
    public PrivateKey getPrivateKey() {
        // your existing SEC1 encoding (compressed)
        return HDKeySerializer.deserializePrivateKey(pubKeyBytes);              
    }
        
    public PublicKey getPublicKey() {
        // your existing SEC1 encoding (compressed)
        return HDKeySerializer.deserializePublicKey(pubKeyBytes);              
    }
        
    public byte[] getPubKeyHash160() throws NoSuchAlgorithmException {
        return Script.hash160(getPubKeyBytes());   
    } 
        
    public byte[] getPubScriptBytes() throws NoSuchAlgorithmException {
        return getPubKeyScript().getScriptBytes();
    } 
    
    public Script getPubKeyScript() throws NoSuchAlgorithmException {
        Script script = Script.p2wpkh(Script.hash160(pubKeyBytes));
        return script;
    } 
    
    public String getPubScriptAddress() throws NoSuchAlgorithmException {
        Script script = getPubKeyScript();   
        String localAddress = Script.p2wpkhScriptToAddress(script, net);
        return localAddress;  
    }
    
    public ECDSASignature sign(byte[] sighash) {

        ECDSASigner signer =
                new ECDSASigner(
                        new HMacDSAKCalculator(
                                new SHA256Digest()
                        )
                );

        ECPrivateKeyParameters priv =
                new ECPrivateKeyParameters(privKey, DOMAIN);

        signer.init(true, priv);

        BigInteger[] sigs = signer.generateSignature(sighash);

        BigInteger r = sigs[0];
        BigInteger s = sigs[1];

        // low-S normalization
        BigInteger halfCurveOrder =
                CURVE_PARAMS.getN().shiftRight(1);

        if (s.compareTo(halfCurveOrder) > 0) {
            s = CURVE_PARAMS.getN().subtract(s);
        }

        return new ECDSASignature(r, s);
    }
       
    public void setUsed(boolean used)    { this.used   = used;   }
    public void setUsedAt(long usedAt)   { this.usedAt = usedAt; }
    
    public String     getKeyId()      { return keyId; }
    public String     getAddress()    { return address; }
    public boolean    isUsed()        { return used; }
    public boolean    isChange()        { return change; }
    //public void       markUsed()      { this.used = true; }

    @Override
    public String toString() {
        return String.format("ECKey{keyId='%s', address='%s', used=%b}",
            keyId, address, used);
    }
}s