package com.verityai.plugin.util;

import com.verityai.plugin.VerityAI;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-player cooldown, requests-per-minute, an in-flight lock
 * (prevents a player firing a second request while the first is still
 * processing), a rough daily token budget, PLUS a server-wide requests/minute
 * cap and a max-concurrent-requests limit so one enthusiastic burst of
 * players can't overload the AI backend or the server's own thread pool.
 *
 * All limits are read live from ConfigManager on every check, so /verity
 * reload picks up new values immediately without needing to reconstruct
 * this object (and losing in-flight/window state).
 */
public class RateLimiter {

    private final VerityAI plugin;

    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<UUID, Window> requestWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> tokensUsedToday = new ConcurrentHashMap<>();
    private final Map<UUID, LocalDate> tokenResetDate = new ConcurrentHashMap<>();

    private final Window globalWindow = new Window();
    private volatile Semaphore concurrencySlots;
    private volatile int concurrencySlotsConfiguredFor = -1;

    public RateLimiter(VerityAI plugin) {
        this.plugin = plugin;
    }

    public enum Result { OK, ON_COOLDOWN, RATE_LIMITED, TOKEN_LIMIT, BUSY, SERVER_BUSY }

    public Result check(org.bukkit.entity.Player player) {
        var cfg = plugin.getConfigManager();
        UUID uuid = player.getUniqueId();

        if (Boolean.TRUE.equals(inFlight.get(uuid))) {
            return Result.BUSY;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = cfg.getEffectiveCooldownSeconds(player) * 1000L;
        Long last = lastRequest.get(uuid);
        if (cooldownMillis > 0 && last != null && (now - last) < cooldownMillis) {
            return Result.ON_COOLDOWN;
        }

        int maxPerMinute = cfg.getEffectiveMaxRequestsPerMinute(player);
        if (maxPerMinute > 0) {
            Window window = requestWindows.computeIfAbsent(uuid, k -> new Window());
            window.purgeOlderThan(now - 60_000L);
            if (window.size() >= maxPerMinute) {
                return Result.RATE_LIMITED;
            }
        }

        int globalMaxPerMinute = cfg.getGlobalMaxRequestsPerMinute();
        if (globalMaxPerMinute > 0) {
            globalWindow.purgeOlderThan(now - 60_000L);
            if (globalWindow.size() >= globalMaxPerMinute) {
                return Result.SERVER_BUSY;
            }
        }

        int effectiveTokenLimit = cfg.getEffectiveMaxTokensPerDay(player);
        if (effectiveTokenLimit > 0 && getTokensUsedToday(uuid) >= effectiveTokenLimit) {
            return Result.TOKEN_LIMIT;
        }

        if (!concurrencySlots().tryAcquire()) {
            return Result.SERVER_BUSY;
        }
        // We only "peeked" for availability here; release immediately — the real
        // acquire-for-the-duration-of-the-request happens in markStarted/markFinished.
        concurrencySlots().release();

        return Result.OK;
    }

    public void markStarted(UUID uuid) {
        long now = System.currentTimeMillis();
        lastRequest.put(uuid, now);
        requestWindows.computeIfAbsent(uuid, k -> new Window()).add(now);
        globalWindow.add(now);
        inFlight.put(uuid, true);
        concurrencySlots().acquireUninterruptibly();
    }

    public void markFinished(UUID uuid) {
        inFlight.put(uuid, false);
        concurrencySlots().release();
    }

    public void addTokensUsed(UUID uuid, int tokens) {
        resetIfNewDay(uuid);
        tokensUsedToday.computeIfAbsent(uuid, k -> new AtomicInteger()).addAndGet(Math.max(0, tokens));
    }

    public int getTokensUsedToday(UUID uuid) {
        resetIfNewDay(uuid);
        return tokensUsedToday.getOrDefault(uuid, new AtomicInteger()).get();
    }

    private void resetIfNewDay(UUID uuid) {
        LocalDate today = LocalDate.now();
        LocalDate last = tokenResetDate.get(uuid);
        if (last == null || !last.equals(today)) {
            tokenResetDate.put(uuid, today);
            tokensUsedToday.put(uuid, new AtomicInteger());
        }
    }

    /** Lazily (re)builds the concurrency semaphore if the configured limit has changed. */
    private synchronized Semaphore concurrencySlots() {
        int configured = Math.max(1, plugin.getConfigManager().getMaxConcurrentRequests());
        if (concurrencySlots == null || concurrencySlotsConfiguredFor != configured) {
            concurrencySlots = new Semaphore(configured);
            concurrencySlotsConfiguredFor = configured;
        }
        return concurrencySlots;
    }

    /** Tiny thread-safe timestamp ring used for per-minute sliding windows (per-player and global). */
    private static class Window {
        private final java.util.ArrayDeque<Long> timestamps = new java.util.ArrayDeque<>();

        synchronized void add(long ts) {
            timestamps.addLast(ts);
        }

        synchronized void purgeOlderThan(long cutoff) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
        }

        synchronized int size() {
            return timestamps.size();
        }
    }
}
