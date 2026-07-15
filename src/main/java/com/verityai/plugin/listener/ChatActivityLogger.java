package com.verityai.plugin.listener;

import com.verityai.plugin.VerityAI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps a capped, in-memory rolling log of today's public chat so Verity can
 * answer questions like "what happened today?" / "امروز چه اتفاقی افتاد؟"
 * with a real (if short) summary instead of guessing. Resets at midnight;
 * intentionally NOT persisted to disk — this is a "recent activity" feature,
 * not a permanent chat log (use a dedicated logging plugin for that).
 */
public class ChatActivityLogger implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final VerityAI plugin;
    private final Deque<String> today = new ArrayDeque<>();
    private LocalDate currentDay = LocalDate.now();

    public ChatActivityLogger(VerityAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        record(event.getPlayer().getName(), event.getMessage());
    }

    public synchronized void record(String playerName, String message) {
        rolloverIfNewDay();
        today.addLast(LocalTime.now().format(TIME) + " " + playerName + ": " + message);
        int max = Math.max(10, plugin.getConfigManager().getChatSummaryMaxLines());
        while (today.size() > max) {
            today.pollFirst();
        }
    }

    /** Today's chat log so far, capped to a character budget so it doesn't blow out a prompt. */
    public synchronized String getTodayLogText(int maxChars) {
        rolloverIfNewDay();
        StringBuilder sb = new StringBuilder();
        // Walk from most recent backwards so the freshest messages survive the char cap.
        java.util.List<String> lines = new java.util.ArrayList<>(today);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (sb.length() + line.length() > maxChars) break;
            sb.insert(0, line + "\n");
        }
        return sb.toString().stripTrailing();
    }

    private void rolloverIfNewDay() {
        LocalDate now = LocalDate.now();
        if (!now.equals(currentDay)) {
            today.clear();
            currentDay = now;
        }
    }
}
