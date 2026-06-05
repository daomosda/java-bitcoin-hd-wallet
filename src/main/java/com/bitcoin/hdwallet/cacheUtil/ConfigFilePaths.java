package com.bitcoin.hdwallet.cacheUtil;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author CONALDES
 */

public class ConfigFilePaths {	
     
    public static Path addressContrlLog() {
        String LOG_DIR = System.getProperty("user.home") 
                + "/.bitcoinlike/addrctl";   

        String LOGFILE = LOG_DIR + "/addressCtl.log";

        Path LOG_FILE = Paths.get(LOGFILE);  
        return LOG_FILE;
    } 
    
    public static String addressDir() {
        String LOG_DIR = System.getProperty("user.home") 
                    + "/.bitcoinlike/addrctl";  
        return LOG_DIR;
    }  
    
    public static String addressFileLog() {
        String LOG_DIR = System.getProperty("user.home") 
                    + "/.bitcoinlike/addrlog";  
        
        String addrFile = LOG_DIR + "addresses.log";
        return addrFile;
    } 
    
    public static String appMessageDir() {
        String LOG_DIR = System.getProperty("user.home")
                + "/.bitcoinlike/appmsg"; 
        return LOG_DIR;
    }  
    
    public static String appMessageLogs() {
        String LOG_DIR = System.getProperty("user.home")
                + "/.bitcoinlike/appmsg"; 
        
        String logFile = LOG_DIR + "/hdwappmsg.txt";
        return logFile;
    }  

    public static String chainDBPath() {
        File baseDir = new File(System.getProperty("user.home"), 
                ".bitcoinlike/chaindbpath");
        if (!baseDir.exists()) baseDir.mkdirs();

        String chainPath = new File(baseDir, "blockchain.db").getAbsolutePath();
        return chainPath;
    }

    public static String hdKeyDBPath() {
        File dir = new File(System.getProperty("user.home"), 
                ".bitcoinlike/hdwltk");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String  dbPath = new File(dir, "hdwKeys.db").getAbsolutePath();
        return dbPath;
    }

    public static Path btcConfigPath() {        
        //Path bitcoinConfigDir = Paths.get(
        //        System.getProperty("user.home"),
        //        "AppData", "Roaming", "Bitcoin/nodembtcconf"
        //);
        
        Path filePath = Path.of("src/resources/bitcoin.conf");
        
        //Path filePath = bitcoinConfigDir.resolve("nmbitcoin.conf");
        return filePath;
    }

    public static Path masterKeyPath() {
        Path repoPath = Path.of(
                System.getProperty("user.home"),
                ".msthdwkp", "wltkey",
                "master_keys.json"
        );
        return repoPath;
    }
    
    public static Path masterSeedPath() {
        Path repoPath = Path.of(
                System.getProperty("user.home"),
                ".msthdwkp", "wltseed",
                "wallet_seed.json"
        );
        return repoPath;
    }
}
