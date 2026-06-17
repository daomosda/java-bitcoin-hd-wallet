/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.core;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.cacheUtil.ConfigFilePaths;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;

/**
 * Application-wide logger.
 * Writes all messages to a .txt file — appends on every run.
 * Also prints to console simultaneously.
 *
 * Usage:
 *   AppLogger.info("Block mined: " + hash);
 *   AppLogger.warn("Low balance: " + balance);
 *   AppLogger.error("Broadcast failed", exception);
 */

public class AppLogger {
    
    private static final String LOG_DIR  = ConfigFilePaths.appMessageDir();
    private static final String LOG_FILE = ConfigFilePaths.appMessageLogs();
    
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern(DATE_PATTERN);

    private static String logged_msg;
    private static PrintWriter writer;
    private static boolean initialized = false;

    // 1. Define Log Levels
    public enum LogLevel {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);

        private final int value;

        LogLevel(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
    
    public static synchronized void initialize() {
        if (initialized) return;

        String threadName = Thread.currentThread().getName();
        try {
            Files.createDirectories(Paths.get(LOG_DIR));

            FileWriter     fw  = new FileWriter(LOG_FILE, true);
            BufferedWriter bw  = new BufferedWriter(fw);
            writer             = new PrintWriter(bw, true); // autoFlush = true

            initialized = true;

            writeSeparator();
            write("Session started — " + LocalDateTime.now().format(FORMATTER));

            //System.out.println("[CustomLogger] Logging to: " + LOG_FILE);

        } catch (IOException e) {
            System.err.println("[" + threadName + "CustomLogger] Failed to initialize: "
                + e.getMessage());
        }
    }

    // 2. Logic to format the string with "{}" placeholders
    private static class MessageFormatter {
        
        public static String format(String message, Object... args) {
            if (message == null) {
                return "";
            }
            if (args == null || args.length == 0) {
                return message;
            }

            StringBuilder sb = new StringBuilder(message.length() + 50);
            int start = 0;
            int argIndex = 0;

            while (start < message.length() && argIndex < args.length) {
                // Find the next pair of braces
                int braceIndex = message.indexOf("{}", start);
                
                if (braceIndex == -1) {
                    break; // No more placeholders
                }

                // Append text before the brace
                sb.append(message, start, braceIndex);
                
                // Append the argument (handles null automatically)
                sb.append(args[argIndex]);
                
                // Move cursor past the {}
                start = braceIndex + 2;
                argIndex++;
            }

            // Append the rest of the message if any
            sb.append(message.substring(start));

            return sb.toString();
        }
    }

    // 3. Logger Instance State
    //private static String name;
    private static LogLevel currentLevel = LogLevel.INFO;

    public void setLevel(LogLevel level) {
        AppLogger.currentLevel = level;
    }

    // 4. Main Logging Method
    private static void log(LogLevel level, String className, String message, Object... args) {
        // If the message level is lower than current setting, ignore it
        if (level.getValue() < currentLevel.getValue()) {
            return;
        }

        String formattedMessage = MessageFormatter.format(message, args);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        //String threadName = Thread.currentThread().getName();

        // Build the final log string
        //String logLine = String.format("[%s] [%s] [%s] %s - %s", 
        String logLine = String.format("[%s] [%s] %s - %s", 
                timestamp, 
                level,  
                className, 
                formattedMessage);
        logged_msg = logLine;
        // Print to standard err for errors, out for everything else
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            System.err.println(logLine);
        } else {
            System.out.println(logLine);
        }
    }

    // 5. Convenience API methods (The ones you asked for)
    public static void trace(String className, String message, Object... args) {
        log(LogLevel.TRACE, className, message, args);
        write(logged_msg);
    }

    public static void debug(String className, String message, Object... args) {
        log(LogLevel.DEBUG, className, message, args);
        write(logged_msg);
    }

    public static void info(String className, String message, Object... args) {
        log(LogLevel.INFO, className, message, args);
        write(logged_msg);
    }

    public static void warn(String className, String message, Object... args) {
        log(LogLevel.WARN, className, message, args);
        write(logged_msg);
    }

    public static void error(String className, String message, Object... args) {
        log(LogLevel.ERROR, className, message, args);
        write(logged_msg);
    }
    
    private static synchronized void write(String message) {

        if (!initialized) initialize();
        
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }    
        
    public static synchronized void close() {
        if (writer != null) {
            write("Session ended.");
            writeSeparator();
            writer.flush();
            writer.close();
            writer      = null;
            initialized = false;
            String threadName = Thread.currentThread().getName();
            System.out.println("[" + threadName + "] Log file closed.");
        }
    }
        
    private static synchronized void writeSeparator() {
        String sep = "─".repeat(80);
        if (writer != null) {
            writer.println(sep);
            writer.flush();
        }
        System.out.println(sep);
    }      
}
