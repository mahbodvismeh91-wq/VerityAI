package com.verityai.plugin.stats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory (not persisted across restarts) counters for a lightweight
 * "dashboard" of Verity's usage, viewable in-game with /verity stats. There's
 * no web server here — this is the practical equivalent for a Bukkit plugin.
 */
public class StatsService {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong totalTokensUsed = new AtomicLong();
    private final AtomicLong totalResponseTimeMs = new AtomicLong();
    private final AtomicLong commandsExecuted = new AtomicLong();
    private final Map<String, AtomicInteger> requestsPerPlayer = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestsPerModel = new ConcurrentHashMap<>();
    private final long startedAt = System.currentTimeMillis();

    public void recordSuccess(String playerName, String model, long durationMs, int tokens) {
        totalRequests.incrementAndGet();
        successfulRequests.incrementAndGet();
        totalTokensUsed.addAndGet(Math.max(0, tokens));
        totalResponseTimeMs.addAndGet(Math.max(0, durationMs));
        requestsPerPlayer.computeIfAbsent(playerName, k -> new AtomicInteger()).incrementAndGet();
        requestsPerModel.computeIfAbsent(model, k -> new AtomicInteger()).incrementAndGet();
    }

    public void recordFailure() {
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
    }

    public void recordCommandExecuted() {
        commandsExecuted.incrementAndGet();
    }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getSuccessfulRequests() { return successfulRequests.get(); }
    public long getFailedRequests() { return failedRequests.get(); }
    public long getTotalTokensUsed() { return totalTokensUsed.get(); }
    public long getCommandsExecuted() { return commandsExecuted.get(); }

    public double getAverageResponseMs() {
        long successes = successfulRequests.get();
        return successes == 0 ? 0 : (double) totalResponseTimeMs.get() / successes;
    }

    public long getUptimeMinutes() {
        return (System.currentTimeMillis() - startedAt) / 60_000L;
    }

    /** Top N players by number of requests, most active first. */
    public java.util.List<Map.Entry<String, Integer>> topPlayers(int limit) {
        return requestsPerPlayer.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().get()))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .toList();
    }

    public Map<String, AtomicInteger> getRequestsPerModel() {
        return requestsPerModel;
    }
}
