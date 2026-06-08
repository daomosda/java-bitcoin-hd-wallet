package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import java.io.BufferedReader;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Minimal BIP-39 implementation: English wordlist, mnemonic <-> entropy, seed derivation.
 */
public final class Bip39Mnemonic {

    private static final int PBKDF2_ROUNDS = 2048;
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA512";
    private static final String SALT_PREFIX = "mnemonic";

    private static final List<String> WORDLIST = loadEnglishWordlist();

    private final List<String> words;

    private Bip39Mnemonic(List<String> words) {
        this.words = Collections.unmodifiableList(new ArrayList<>(words));
    }

    // -------- Public factory methods --------

    /** Generate a new mnemonic with the given word count (12/15/18/21/24).
     * @param wordCount
     * @return  */
    public static Bip39Mnemonic generate(int wordCount) {
        int entropyBits;
        switch (wordCount) {
            case 12 -> entropyBits = 128;
            case 15 -> entropyBits = 160;
            case 18 -> entropyBits = 192;
            case 21 -> entropyBits = 224;
            case 24 -> entropyBits = 256;
            default -> throw new IllegalArgumentException("Invalid word count: " + wordCount);
        }

        byte[] entropy = new byte[entropyBits / 8];
        new SecureRandom().nextBytes(entropy);
        List<String> words = entropyToWords(entropy);
        return new Bip39Mnemonic(words);
    }

    /** Create from existing words (will validate checksum).
     * @param words
     * @return  */
    public static Bip39Mnemonic fromWords(List<String> words) {
        validateMnemonic(words);
        return new Bip39Mnemonic(words);
    }

    // -------- Core BIP-39 logic --------

    public static List<String> entropyToWords(byte[] entropy) {
        if (entropy.length < 16 || entropy.length > 32 || entropy.length % 4 != 0) {
            throw new IllegalArgumentException("Entropy length must be 128–256 bits, multiple of 32");
        }

        int entBits = entropy.length * 8;
        int checksumBits = entBits / 32;

        byte[] hash = sha256(entropy);
        int checksum = (hash[0] & 0xFF) >> (8 - checksumBits);

        BigInteger entInt = new BigInteger(1, entropy);
        BigInteger csInt = BigInteger.valueOf(checksum);
        BigInteger combined = entInt.shiftLeft(checksumBits).or(csInt);

        int numWords = (entBits + checksumBits) / 11;
        List<String> out = new ArrayList<>(numWords);
        for (int i = numWords - 1; i >= 0; i--) {
            int idx = combined.shiftRight(i * 11).and(BigInteger.valueOf(0x7FF)).intValue();
            out.add(WORDLIST.get(idx));
        }
        return out;
    }

    public static byte[] wordsToEntropy(List<String> words) {
        int wordCount = words.size();
        if (wordCount % 3 != 0) {
            throw new IllegalArgumentException("Word count must be multiple of 3");
        }

        int entBits = wordCount * 11 * 32 / 33;
        int csBits = wordCount * 11 - entBits;

        BigInteger acc = BigInteger.ZERO;
        for (String w : words) {
            int idx = WORDLIST.indexOf(w);
            if (idx < 0) throw new IllegalArgumentException("Word not in wordlist: " + w);
            acc = acc.shiftLeft(11).or(BigInteger.valueOf(idx));
        }

        BigInteger entInt = acc.shiftRight(csBits);
        BigInteger csInt = acc.and(BigInteger.valueOf((1L << csBits) - 1));

        byte[] entropy = toFixedLength(entInt.toByteArray(), entBits / 8);
        byte[] hash = sha256(entropy);
        int checksum = (hash[0] & 0xFF) >> (8 - csBits);
        if (csInt.intValue() != checksum) {
            throw new IllegalArgumentException("Invalid mnemonic checksum");
        }
        return entropy;
    }

    public static void validateMnemonic(List<String> words) {
        wordsToEntropy(words); // throws if invalid
    }

    /** Derive 64-byte seed from mnemonic + optional passphrase (BIP-39).
     * @param passphrase
     * @return  */
    public byte[] toSeed(String passphrase) {
        String mnemonicStr = String.join(" ", words);
        String salt = SALT_PREFIX + (passphrase == null ? "" : passphrase);
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    mnemonicStr.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8),
                    PBKDF2_ROUNDS,
                    64 * 8
            );
            SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("BIP39 seed derivation failed", e);
        }
    }

    // -------- Accessors --------

    public List<String> getWords() {
        return words;
    }
    
    public String getMnemonic() {
        return SALT_PREFIX;
    }

    @Override
    public String toString() {
        return String.join(" ", words);
    }

    // -------- Helpers --------
   
    private static List<String> loadEnglishWordlist() {        
        List<String> wordsList = new ArrayList<>();
        List<String> lines = null;
        try (InputStream is = Bip39Mnemonic.class.getClassLoader().getResourceAsStream("bip39_english.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            lines = reader.lines().collect(Collectors.toList());
        } catch (IOException ex) {
            System.getLogger(Bip39Mnemonic.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
        
        for (String line : lines) {
            String[] words = line.split(",");
            wordsList.addAll(Arrays.asList(words));
        }
        System.out.println("wordsList.size() = " + wordsList.size());
        
        return wordsList;
    }    
       
    private static byte[] sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static byte[] toFixedLength(byte[] in, int len) {
        byte[] out = new byte[len];
        if (in.length == len) return in;
        if (in.length > len) {
            System.arraycopy(in, in.length - len, out, 0, len);
        } else {
            System.arraycopy(in, 0, out, len - in.length, in.length);
        }
        return out;
    }
}