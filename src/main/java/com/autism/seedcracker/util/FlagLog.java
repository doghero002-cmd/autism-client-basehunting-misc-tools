package com.autism.seedcracker.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Flag / issue logger.
 *
 * A single append-only log the Flag Detector (and any module) writes structured events to, so the
 * client can be improved from real anti-cheat behaviour. Each line is a pipe-delimited record:
 *
 *   <timestamp> | <severity> | <category> | <module> | <key=value> ...
 *
 * Categories: SETBACK, ROTATION, BREAK_DESYNC, PLACE_FAIL, SLOT_DESYNC, KICK, MOVEMENT, PACKET,
 * INFO. Severity: INFO, WARN, FLAG.
 *
 * The file lives at <autism-client-folder>/flag-log.txt and is flushed on every write so nothing
 * is lost on a kick/crash. {@link #path()} exposes it for review.
 */
public final class FlagLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Path file;
    private static BufferedWriter writer;
    private static boolean initFailed = false;

    private FlagLog() {}

    public enum Severity { INFO, WARN, FLAG }

    /** The log file path (created lazily). */
    public static synchronized Path path() {
        ensureInit();
        return file;
    }

    /** Log an event. Safe to call from any thread / any module; never throws. */
    public static void log(Severity severity, String category, String module, String detail) {
        try {
            ensureInit();
            if (writer == null) return;
            String line = LocalDateTime.now().format(TS)
                + " | " + severity
                + " | " + safe(category)
                + " | " + safe(module)
                + " | " + safe(detail);
            synchronized (FlagLog.class) {
                writer.write(line);
                writer.newLine();
                writer.flush(); // survive kicks/crashes
            }
        } catch (Throwable ignored) {}
    }

    public static void info(String category, String module, String detail) { log(Severity.INFO, category, module, detail); }
    public static void warn(String category, String module, String detail) { log(Severity.WARN, category, module, detail); }
    public static void flag(String category, String module, String detail) { log(Severity.FLAG, category, module, detail); }

    private static String safe(String s) {
        return s == null ? "-" : s.replace('|', '/').replace("\n", " ").replace("\r", " ");
    }

    private static synchronized void ensureInit() {
        if (writer != null || initFailed) return;
        try {
            Path dir;
            try {
                dir = autismclient.AutismClientAddon.FOLDER.toPath();
            } catch (Throwable t) {
                // Fallback: Fabric config dir + autism.
                dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("autism");
            }
            Files.createDirectories(dir);
            file = dir.resolve("flag-log.txt");
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Runtime.getRuntime().addShutdownHook(new Thread(FlagLog::close));
        } catch (Throwable t) {
            initFailed = true;
            writer = null;
        }
    }

    private static synchronized void close() {
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        writer = null;
    }
}
