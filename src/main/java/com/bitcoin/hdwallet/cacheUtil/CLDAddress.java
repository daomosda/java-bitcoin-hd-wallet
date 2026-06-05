package com.bitcoin.hdwallet.cacheUtil;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 *
 * @author CONALDES
 */

public class CLDAddress {   
    
    public static void saveCLDAddress(String LOG_DIR , Path LOG_FILE, String msgStr) {
        try {                    
            // Create log directory if needed
            Files.createDirectories(Paths.get(LOG_DIR));
            // Files.write can create the file if it doesn't exist and append to it
            Files.write(LOG_FILE, 
                    msgStr.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("ERROR: Could not write to log file: " + e.getMessage());
        }            
    }  

    public static String readCLDAddress(Path LOG_FILE) {
        String addr;
        try {            
            addr = Files.readString(LOG_FILE, StandardCharsets.UTF_8);   //readAllLines(LOG_FILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ERROR: Could not read log file: " + e.getMessage());  
            addr = "";
        }
        return addr;
    }
}
