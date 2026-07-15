package com.verityai.plugin.util;

import com.verityai.plugin.VerityAI;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight logger for debug output and error persistence.
 * Debug lines only print when debug.enabled is true in config.yml.
 */
public class DebugLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VerityAI plugin;
    private final Path errorLog;
    private final Path requestLog;

    public DebugLogger(VerityAI plugin) {
        this.plugin = plugin;
        Path dataFolder = plugin.getDataFolder().toPath();
        Path logsFolder = dataFolder.resolve("logs");
        try {
            Files.createDirectories(logsFolder);
        } catch (IOException ignored) {
            // handled lazily on write
        }
        this.errorLog = logsFolder.resolve("errors.log");
        this.requestLog = logsFolder.resolve("requests.log");
    }

    public void debug(String message) {
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    public void requestLog(String playerName, String question, String model, long durationMs) {
        if (!plugin.getConfigManager().isLogRequests()) {
            return;
        }
        String line = String.format("[%s] %s -> model=%s time=%dms | %s",
                LocalDateTime.now().format(TS), playerName, model, durationMs, singleLine(question));
        appendLine(requestLog, line);
    }

    public void errorLog(String context, Throwable t) {
        if (!plugin.getConfigManager().isLogErrors()) {
            return;
        }
        String line = String.format("[%s] %s -> %s", LocalDateTime.now().format(TS), context, t);
        appendLine(errorLog, line);
    }

    private String singleLine(String text) {
        return text == null ? "" : text.replace("\n", " ").trim();
    }

    private void appendLine(Path file, String line) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file.toFile(), true))) {
            writer.println(line);
        } catch (IOException e) {
            plugin.getLogger().warning("VerityAI: could not write log file " + file + ": " + e.getMessage());
        }
    }
}
