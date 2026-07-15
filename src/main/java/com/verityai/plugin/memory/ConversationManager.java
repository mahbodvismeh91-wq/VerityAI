package com.verityai.plugin.memory;

import com.verityai.plugin.VerityAI;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a short rolling window of the recent conversation per player (in
 * memory only, lost on restart) plus "conversation mode" state so players
 * can keep chatting with Verity without retyping the trigger every time.
 *
 * Thread safety: the outer maps are ConcurrentHashMap (safe for concurrent
 * access by different players), but each player's own Deque is NOT
 * thread-safe on its own, so every read/mutation of a single player's deque
 * is done inside a `synchronized (deque)` block — this only ever blocks
 * concurrent requests from the *same* player (which shouldn't happen anyway,
 * since RateLimiter already serializes a player's own requests), never
 * across different players like the old class-wide `synchronized` methods did.
 */
public class ConversationManager {

    /** One turn of the conversation. */
    public record Turn(String role, String content) {}

    private final VerityAI plugin;

    private final Map<UUID, Deque<Turn>> history = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activeConversationUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivityMillis = new ConcurrentHashMap<>();
    private final Map<UUID, StringBuilder> condensedSummary = new ConcurrentHashMap<>();

    public ConversationManager(VerityAI plugin) {
        this.plugin = plugin;
    }

    private int shortTermLimit() {
        return Math.max(1, plugin.getConfigManager().getShortTermLimit());
    }

    public void addUserMessage(UUID uuid, String content) {
        // Spam guard: don't grow the stored context with an exact repeat of the player's last message.
        Deque<Turn> deque = history.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (deque) {
            Turn last = deque.peekLast();
            if (last != null && "user".equals(last.role()) && last.content().equalsIgnoreCase(content)) {
                touch(uuid);
                return;
            }
        }
        add(uuid, new Turn("user", content));
    }

    public void addAssistantMessage(UUID uuid, String content) {
        add(uuid, new Turn("assistant", content));
    }

    private void add(UUID uuid, Turn turn) {
        Deque<Turn> deque = history.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        int limit = shortTermLimit() * 2; // user+assistant pairs
        synchronized (deque) {
            deque.addLast(turn);
            while (deque.size() > limit) {
                Turn dropped = deque.pollFirst();
                if (dropped != null) {
                    condenseIntoSummary(uuid, dropped);
                }
            }
        }
        touch(uuid);
    }

    /** Rolls an evicted turn into a compact "earlier in the conversation" note instead of losing it outright. */
    private void condenseIntoSummary(UUID uuid, Turn dropped) {
        int cap = plugin.getConfigManager().getSummaryMaxChars();
        StringBuilder summary = condensedSummary.computeIfAbsent(uuid, k -> new StringBuilder());
        synchronized (summary) {
            String snippet = dropped.content().replace("\n", " ").trim();
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }
            summary.append(dropped.role().equals("user") ? "Player said: " : "You said: ")
                    .append(snippet).append(" | ");
            // Keep only the most recent tail once it grows past the configured cap.
            if (summary.length() > cap) {
                summary.delete(0, summary.length() - cap);
            }
        }
    }

    /** A short condensed note covering turns that have already scrolled out of short-term memory. */
    public String getCondensedSummary(UUID uuid) {
        StringBuilder summary = condensedSummary.get(uuid);
        if (summary == null) return "";
        synchronized (summary) {
            return summary.toString();
        }
    }

    /** Replaces the condensed summary outright — used when a real AI-generated compression is available. */
    public void setCondensedSummary(UUID uuid, String text) {
        StringBuilder summary = condensedSummary.computeIfAbsent(uuid, k -> new StringBuilder());
        synchronized (summary) {
            summary.setLength(0);
            summary.append(text == null ? "" : text.trim());
        }
    }

    public Deque<Turn> getHistory(UUID uuid) {
        Deque<Turn> deque = history.get(uuid);
        if (deque == null) return new ArrayDeque<>();
        synchronized (deque) {
            return new ArrayDeque<>(deque);
        }
    }

    /** Clears everything for a player: short-term history, condensed summary, and conversation mode. */
    public void clear(UUID uuid) {
        history.remove(uuid);
        activeConversationUntil.remove(uuid);
        lastActivityMillis.remove(uuid);
        condensedSummary.remove(uuid);
    }

    /** Marks the player as actively mid-conversation with Verity for N seconds. */
    public void refreshConversationWindow(UUID uuid) {
        long timeoutMillis = plugin.getConfigManager().getConversationTimeoutSeconds() * 1000L;
        if (timeoutMillis > 0) {
            activeConversationUntil.put(uuid, System.currentTimeMillis() + timeoutMillis);
        }
        touch(uuid);
    }

    public boolean isInActiveConversation(UUID uuid) {
        Long until = activeConversationUntil.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    public void endConversation(UUID uuid) {
        activeConversationUntil.remove(uuid);
    }

    private void touch(UUID uuid) {
        lastActivityMillis.put(uuid, System.currentTimeMillis());
    }

    /**
     * Drops short-term history/summary for players who've been idle longer than
     * memory.idle-clear-minutes. Safe to call periodically from an async task —
     * only ever removes map entries, never mutates a live deque directly.
     */
    public void purgeIdleConversations() {
        int idleMinutes = plugin.getConfigManager().getConversationIdleClearMinutes();
        if (idleMinutes <= 0) return; // 0 = disabled

        long cutoff = System.currentTimeMillis() - (idleMinutes * 60_000L);
        for (Map.Entry<UUID, Long> entry : lastActivityMillis.entrySet()) {
            if (entry.getValue() < cutoff) {
                UUID uuid = entry.getKey();
                history.remove(uuid);
                condensedSummary.remove(uuid);
                lastActivityMillis.remove(uuid);
                activeConversationUntil.remove(uuid);
            }
        }
    }
}
