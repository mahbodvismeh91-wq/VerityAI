package com.verityai.plugin.memory;

import com.verityai.plugin.VerityAI;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes every question/answer pair to a per-player, per-day transcript file
 * under plugins/VerityAI/history/<uuid>-<yyyy-MM-dd>.log — separate from
 * short/long-term memory, purely for record-keeping (memory.save-history).
 *
 * Files rotate daily (a new file per calendar day) and cleanupOldLogs() —
 * called once at startup and then periodically — deletes any file older
 * than memory.history-retention-days (0 disables cleanup entirely).
 */
public class HistoryLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VerityAI plugin;
    private final Path historyFolder;

    public HistoryLogger(VerityAI plugin) {
        this.plugin = plugin;
        this.historyFolder = plugin.getDataFolder().toPath().resolve("history");
        try {
            Files.createDirectories(historyFolder);
        } catch (IOException ignored) {
            // handled lazily on write
        }
    }

    public void log(UUID uuid, String playerName, String question, String answer) {
        if (!plugin.getConfigManager().isSaveHistory()) {
            return;
        }
        String day = LocalDate.now().format(DAY);
        Path file = historyFolder.resolve(uuid + "-" + day + ".log");
        String ts = LocalDateTime.now().format(TS);
        try (PrintWriter writer = new PrintWriter(new FileWriter(file.toFile(), true))) {
            writer.println("[" + ts + "] " + playerName + ": " + oneLine(question));
            writer.println("[" + ts + "] Verity: " + oneLine(answer));
        } catch (IOException e) {
            plugin.getLogger().warning("VerityAI: failed to write history for " + playerName + ": " + e.getMessage());
        }
    }

    /** Deletes transcript files older than memory.history-retention-days. Safe to call from an async task. */
    public void cleanupOldLogs() {
        int retentionDays = plugin.getConfigManager().getHistoryRetentionDays();
        if (retentionDays <= 0) return; // 0 = keep forever

        long cutoffMillis = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000);
        try (Stream<Path> files = Files.list(historyFolder)) {
            files.filter(p -> p.toString().endsWith(".log"))
                    .forEach(p -> {
                        try {
                            if (Files.getLastModifiedTime(p).toMillis() < cutoffMillis) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException e) {
                            plugin.getLogger().warning("VerityAI: could not check/delete old history file " + p + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("VerityAI: history log cleanup failed: " + e.getMessage());
        }
    }

    private String oneLine(String text) {
        return text == null ? "" : text.replace("\n", " ").trim();
    }
}
