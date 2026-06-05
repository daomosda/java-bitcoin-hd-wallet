package com.bitcoin.hdwallet.cacheUtil;

/**
 *
 * @author CONALDES
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class WordFileStore {

    private final Path filePath;

    public WordFileStore(String filePath) {
        this.filePath = Paths.get(filePath);
    }

    /**
     * Append a single word to the file (one word per line).
     * @param word
     * @throws java.io.IOException
     */
    public void appendWord(String word) throws IOException {
        try (PrintWriter out = new PrintWriter(
                new FileWriter(filePath.toFile(), StandardCharsets.UTF_8, true))) {
            out.println(word);
        }
    }

    /**
     * Append multiple words (each on its own line).
     * @param words
     * @throws java.io.IOException
     */
    public void appendWords(List<String> words) throws IOException {
        try (PrintWriter out = new PrintWriter(
                new FileWriter(filePath.toFile(), StandardCharsets.UTF_8, true))) {
            for (String word : words) {
                out.println(word);
            }
        }
    }

    /**
     * Read all words from the file as a list.
     * If the file does not exist, it is created and an empty list is returned.
     * @return 
     * @throws java.io.IOException
     */
    public Set<String> readWords() throws IOException {
        // Create file and parent directories if they don't exist
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.getParent());
            Files.createFile(filePath);
        }

        Set<String> words = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    words.add(line.trim());
                }
            }
        }
        return words;
    }
}