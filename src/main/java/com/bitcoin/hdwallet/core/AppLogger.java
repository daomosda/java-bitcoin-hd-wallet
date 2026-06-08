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

    private static final String LOG_DIR      = ConfigFilePaths.appMessageDir();  //System.getProperty("user.home")
    //                                          + "/.bitcoinlike/logs";
    private static final String LOG_FILE     = ConfigFilePaths.appMessageLogs();   //LOG_DIR + "/hdwapp.txt";
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern(DATE_PATTERN);

    // Single shared writer — append = true
    private static PrintWriter writer;
    private static boolean     initialized = false;

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Call once at application startup.
     * Creates log directory and file if they do not exist.
     * Appends to existing file — never overwrites.
     */
    public static synchronized void initialize() {
        if (initialized) return;

        try {
            // Create log directory if needed
            Files.createDirectories(Paths.get(LOG_DIR));

            // Open file in APPEND mode — true = append, not overwrite
            FileWriter     fw  = new FileWriter(LOG_FILE, true);
            BufferedWriter bw  = new BufferedWriter(fw);
            writer             = new PrintWriter(bw, true); // autoFlush = true

            initialized = true;

            // Write session separator so runs are clearly distinct
            writeSeparator();
            write("INFO", "AppLogger",
                "Session started — " + LocalDateTime.now().format(FORMATTER));

            System.out.println("[AppLogger] Logging to: " + LOG_FILE);

        } catch (IOException e) {
            System.err.println("[AppLogger] Failed to initialize: "
                + e.getMessage());
        }
    }

    public static void info(String message, Object... args) {
        write("INFO ", Thread.currentThread().getStackTrace()[2].getClassName()
                .replaceAll(".*\\.", ""), format(message, args));
    }

    public static void warn(String message, Object... args) {
        write("WARN ", Thread.currentThread().getStackTrace()[2].getClassName()
                .replaceAll(".*\\.", ""), format(message, args));
    }

    public static void error(String message, Object... args) {
        write("ERROR", Thread.currentThread().getStackTrace()[2].getClassName()
                .replaceAll(".*\\.", ""), format(message, args));
    }

    public static void debug(String message, Object... args) {
        write("DEBUG", Thread.currentThread().getStackTrace()[2].getClassName()
                .replaceAll(".*\\.", ""), format(message, args));
    }

    // ── Helper — replaces {} placeholders with args ───────────────────────
    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;
        StringBuilder sb      = new StringBuilder();
        int           argIdx  = 0;
        int           i       = 0;
        while (i < message.length()) {
            if (i < message.length() - 1
                    && message.charAt(i)     == '{'
                    && message.charAt(i + 1) == '}') {
                // Replace {} with next arg
                sb.append(argIdx < args.length
                        ? args[argIdx++]
                        : "{}");
                i += 2;
            } else {
                sb.append(message.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static String formatMessage(String format, Object... args) {
        for (Object arg : args) {
            format = format.replaceFirst("\\{}", String.valueOf(arg));
        }
        return format;
    }
    
    public static void info(String message) {
        write("INFO ", Thread.currentThread().getStackTrace()[2].getClassName()
            .replaceAll(".*\\.", ""), message);
    }

    public static void info(String tag, String message) {
        write("INFO ", tag, message);
    }

    public static void warn(String message) {
        write("WARN ", Thread.currentThread().getStackTrace()[2].getClassName()
            .replaceAll(".*\\.", ""), message);
    }

    public static void warn(String tag, String message) {
        write("WARN ", tag, message);
    }

    public static void error(String message) {
        write("ERROR", Thread.currentThread().getStackTrace()[2].getClassName()
            .replaceAll(".*\\.", ""), message);
    }

    public static void error(String tag, String message) {
        write("ERROR", tag, message);
    }

    /**
     * Logs an error with full stack trace.
     * @param message
     * @param t
     */
    public static void error(String message, Throwable t) {
        error(message);
        if (writer != null && t != null) {
            synchronized (AppLogger.class) {
                t.printStackTrace(writer); // writes stack trace to file
                writer.flush();
            }
            t.printStackTrace(System.err); // also print to console
        }
    }

    public static void error(String tag, String message, Throwable t) {
        error(tag, message);
        if (writer != null && t != null) {
            synchronized (AppLogger.class) {
                t.printStackTrace(writer);
                writer.flush();
            }
            t.printStackTrace(System.err);
        }
    }

    public static void debug(String message) {
        write("DEBUG", Thread.currentThread().getStackTrace()[2].getClassName()
            .replaceAll(".*\\.", ""), message);
    }

    public static void debug(String tag, String message) {
        write("DEBUG", tag, message);
    }

    // ── Section Markers ───────────────────────────────────────────────────────

    /**
     * Writes a visible section header — useful for marking major events.
     *
     * Example output:
     * ══════════════════════════════════════════════
     *  [2026-04-01 10:32:00.000] ── Block 840000 ──
     * ══════════════════════════════════════════════
     * @param title
     */
    public static void section(String title) {
        String line = "═".repeat(50);
        String ts   = timestamp();
        synchronized (AppLogger.class) {
            if (writer != null) {
                writer.println(line);
                writer.println(" [" + ts + "] ── " + title + " ──");
                writer.println(line);
                writer.flush();
            }
            System.out.println(line);
            System.out.println(" [" + ts + "] ── " + title + " ──");
            System.out.println(line);
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Call on application shutdown — flushes and closes the file.
     * Register via Runtime.getRuntime().addShutdownHook().
     */
    public static synchronized void close() {
        if (writer != null) {
            write("INFO", "AppLogger", "Session ended.");
            writeSeparator();
            writer.flush();
            writer.close();
            writer      = null;
            initialized = false;
            System.out.println("[AppLogger] Log file closed.");
        }
    }

    // ── Core Write ────────────────────────────────────────────────────────────

    /**
     * Writes a formatted log line to both file and console.
     *
     * Format:
     * [2026-04-01 10:32:00.123] [INFO ] [ClassName   ] Message here
     */
    private static synchronized void write(
            String level, String tag, String message) {

        if (!initialized) initialize(); // auto-init if needed

        String line = String.format("[%s] [%-5s] [%-30s] %s",
            timestamp(), level, tag, message);

        // Write to file
        if (writer != null) {
            writer.println(line);
            writer.flush();
        }

        // Write to console
        if (level.contains("ERROR")) {
            System.err.println(line);
        } else {
            System.out.println(line);
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

    private static String timestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }
}