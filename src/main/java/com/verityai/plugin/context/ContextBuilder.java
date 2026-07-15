package com.verityai.plugin.context;

import com.verityai.plugin.VerityAI;
import com.verityai.plugin.integration.HookManager;
import com.verityai.plugin.memory.LongTermMemoryStore;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Builds the full system prompt sent to the AI: base personality, server
 * info, live player/world facts, and (when the player's message contains
 * relevant keywords) computed facts like nearest village or nearest player —
 * this is a lightweight "context injection" layer rather than full
 * function-calling, so the model always answers from real data instead of
 * guessing coordinates.
 */
public class ContextBuilder {

    private final VerityAI plugin;
    private final WorldQueryService worldQueryService;

    public ContextBuilder(VerityAI plugin, WorldQueryService worldQueryService) {
        this.plugin = plugin;
        this.worldQueryService = worldQueryService;
    }

    public String buildSystemPrompt(Player player, String userMessage) {
        var cfg = plugin.getConfigManager();
        HookManager hooks = plugin.getHookManager();

        StringBuilder sb = new StringBuilder();

        String personality = hooks.parsePlaceholders(player, cfg.getPersonalityPromptForWorld(player.getWorld().getName()));
        sb.append(personality).append("\n\n");

        switch (cfg.getLanguage().toLowerCase(Locale.ROOT)) {
            case "fa" -> sb.append("Always answer in Persian (Farsi), using Persian script.\n");
            case "en" -> sb.append("Always answer in English.\n");
            case "finglish" -> sb.append("Always answer in Finglish: the Persian language written with Latin/English "
                    + "letters (never Persian script, never actual English). Example style: 'salam! chetori? "
                    + "alan too dashboard hastam.'\n");
            default -> sb.append("Answer in the same language and script the player used (Persian, Finglish, or English).\n");
        }

        sb.append("\n--- Server information ---\n");
        appendIfPresent(sb, "Server name", cfg.getServerName());
        appendIfPresent(sb, "Server rules", cfg.getServerRules());
        appendIfPresent(sb, "Server IP", cfg.getServerIp());
        appendIfPresent(sb, "Website", cfg.getServerWebsite());
        appendIfPresent(sb, "Game modes", cfg.getServerGamemodes());
        appendIfPresent(sb, "Server version", cfg.getServerVersion());
        appendIfPresent(sb, "Server owner", cfg.getOwnerName());
        if (cfg.isOwner(player.getName())) {
            sb.append("Note: the player you are talking to right now IS the server owner.\n");
        }

        if (cfg.getCustomKnowledge() != null && !cfg.getCustomKnowledge().isEmpty()) {
            sb.append("\n--- Things the server admin has taught you (config knowledge — always treat as true) ---\n");
            for (String line : cfg.getCustomKnowledge()) {
                if (line != null && !line.isBlank()) {
                    sb.append("- ").append(line).append('\n');
                }
            }
        }

        List<String> lessons = plugin.getLessonsService().getLessons();
        if (!lessons.isEmpty()) {
            sb.append("\n--- Lessons learned from past mistakes (avoid repeating these) ---\n");
            for (String lesson : lessons) {
                sb.append("- ").append(lesson).append('\n');
            }
        }

        sb.append("\n--- Live player & world data (trust these over your own guesses) ---\n");
        Location loc = player.getLocation();
        sb.append("Player name: ").append(player.getName()).append('\n');
        sb.append("Coordinates: x=").append(loc.getBlockX())
                .append(", y=").append(loc.getBlockY())
                .append(", z=").append(loc.getBlockZ()).append('\n');
        sb.append("World: ").append(player.getWorld().getName()).append('\n');
        sb.append("Server TPS: ").append(String.format(Locale.US, "%.1f", worldQueryService.getTps())).append('\n');
        sb.append("Players online: ").append(worldQueryService.getOnlineCount()).append('\n');
        sb.append("Player ping: ").append(player.getPing()).append("ms\n");
        sb.append("In-game time: ").append(worldQueryService.getServerTimeOfDay(player.getWorld())).append('\n');
        sb.append("Held item: ").append(describeItem(player)).append('\n');

        Optional<Double> balance = hooks.getBalance(player);
        balance.ifPresent(bal -> sb.append("Player balance: ").append(String.format(Locale.US, "%.2f", bal)).append('\n'));

        Optional<String> rank = hooks.getPrimaryGroup(player);
        rank.ifPresent(r -> sb.append("Player rank/group: ").append(r).append('\n'));

        List<String> homes = hooks.getHomes(player);
        if (!homes.isEmpty()) {
            sb.append("Player homes: ").append(String.join(", ", homes)).append('\n');
        }

        appendKeywordFacts(sb, player, userMessage);

        if (cfg.isLongTermEnabled()) {
            LongTermMemoryStore store = plugin.getLongTermMemoryStore();
            // With embeddings enabled this returns the facts most relevant to the current
            // question (semantic search) instead of dumping everything known about the
            // player — keeps the prompt small and focused even after many memories pile up.
            List<String> facts = store.getRelevantFacts(player.getUniqueId(), userMessage, cfg.getRelevantFactsLimit());
            if (!facts.isEmpty()) {
                sb.append("\n--- Things you remember about this player ---\n");
                java.util.Map<String, java.util.List<String>> grouped = new java.util.LinkedHashMap<>();
                for (String fact : facts) {
                    String[] parts = LongTermMemoryStore.splitCategory(fact);
                    grouped.computeIfAbsent(parts[0], k -> new java.util.ArrayList<>()).add(parts[1]);
                }
                for (var entry : grouped.entrySet()) {
                    sb.append(capitalize(entry.getKey())).append(": ").append(String.join("; ", entry.getValue())).append('\n');
                }
            }

            if (cfg.isAutoRememberEnabled() && !cfg.isFunctionCallingEnabled()) {
                // When function-calling is enabled, remembering facts goes through the
                // structured remember_fact tool instead of this text-tag convention.
                sb.append("\nIf the player tells you something worth permanently remembering about themselves "
                        + "(their name, an interest, a friend, a project, their playstyle, a future goal, etc.), "
                        + "add one line per fact at the very end of your reply in the EXACT format "
                        + "[REMEMBER: category | short fact here], where category is one of: interest, friend, "
                        + "project, playstyle, goal, general. Example: [REMEMBER: interest | loves building castles]. "
                        + "These lines are saved and then removed before the player sees your reply, so phrase each fact "
                        + "so it stands alone. Never invent facts the player didn't actually tell you.\n");
            }
        }

        if (cfg.isCommandsEnabled() && !cfg.isFunctionCallingEnabled()) {
            // When function-calling is enabled, commands go through the structured
            // run_command tool instead of this text-tag convention.
            List<String> whitelist = cfg.getCommandWhitelist();
            List<String> ownerWhitelist = cfg.isOwner(player.getName()) ? cfg.getOwnerCommandWhitelist() : List.of();
            if ((whitelist != null && !whitelist.isEmpty()) || !ownerWhitelist.isEmpty()) {
                sb.append("\n--- Commands you're allowed to run for this player ---\n");
                if (whitelist != null && !whitelist.isEmpty()) {
                    sb.append("Available to any player: ").append(String.join(", ", whitelist)).append('\n');
                }
                if (!ownerWhitelist.isEmpty()) {
                    sb.append("Available to this player only (server owner): ").append(String.join(", ", ownerWhitelist)).append('\n');
                }
                sb.append("Only trigger one of these if the player clearly and explicitly asked you to perform that "
                        + "action right now. To do so, add a line at the very end of your reply in the EXACT format "
                        + "[[CMD: full command without the leading slash]], e.g. [[CMD: spawn]]. Never invent a command "
                        + "that isn't in the lists above, and never trigger a command the player didn't ask for.\n");
            }
        }

        appendIfMapRequested(sb, player, userMessage);
        appendIfTodaySummaryRequested(sb, userMessage);

        sb.append("\nKeep answers concise and helpful. If asked about coordinates, ")
                .append("distances or server status, use only the live data above — never invent numbers. ")
                .append("If asked to design or plan a build, structure the answer with a rough size/dimensions, ")
                .append("a materials list, and short step-by-step instructions.");

        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private String describeItem(Player player) {
        var item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return "nothing (empty hand)";
        }
        return item.getAmount() + "x " + item.getType().name();
    }

    private void appendKeywordFacts(StringBuilder sb, Player player, String message) {
        String lower = message.toLowerCase(Locale.ROOT);

        try {
            if (containsAny(lower, "village", "روستا", "دهکده")) {
                String result = worldQueryService.findNearestVillage(player).get(5, TimeUnit.SECONDS);
                sb.append("Nearest village: ").append(result).append('\n');
            }

            if (containsAny(lower, "nearest player", "closest player", "نزدیک ترین بازیکن", "نزدیک‌ترین بازیکن")) {
                worldQueryService.findNearestPlayer(player).ifPresentOrElse(
                        p -> sb.append("Nearest player: ").append(p.getName())
                                .append(" (").append(String.format(Locale.US, "%.0f",
                                        p.getLocation().distance(player.getLocation()))).append(" blocks away)\n"),
                        () -> sb.append("Nearest player: no other players in this world.\n")
                );
            }

            if (containsAny(lower, "desert", "بیابان")) {
                String result = worldQueryService.findNearestBiome(player, Biome.DESERT).get(5, TimeUnit.SECONDS);
                sb.append("Nearest desert biome: ").append(result).append('\n');
            }

            if (containsAny(lower, "stronghold", "استرانگهولد", "استرانگ‌هولد")) {
                String result = worldQueryService.findNearestStronghold(player).get(5, TimeUnit.SECONDS);
                sb.append("Nearest stronghold: ").append(result).append('\n');
            }

            if (containsAny(lower, "weather", "biome", "dimension", "هوا", "بیوم", "دایمنشن", "بعد")) {
                String result = worldQueryService.getCurrentEnvironmentSummary(player).get(5, TimeUnit.SECONDS);
                sb.append("Current environment: ").append(result).append('\n');
            }
        } catch (Exception ignored) {
            // Best-effort context enrichment — never block the AI reply on a slow lookup.
        }
    }

    /** When the player asks about "the map", hand the model a real terrain summary instead of a guess. */
    private void appendIfMapRequested(StringBuilder sb, Player player, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (!containsAny(lower, "map", "نقشه", "مپ")) {
            return;
        }
        try {
            String summary = worldQueryService.describeSurroundingTerrain(player).get(5, TimeUnit.SECONDS);
            sb.append("\nNearby terrain (real data, use this instead of guessing): ").append(summary).append('\n');
            sb.append("Tip: tell the player they can run /verity map to see an in-chat mini-map.\n");
        } catch (Exception ignored) {
            // Best-effort — never block the reply on a slow lookup.
        }
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.equals("general")) return "Other notes";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + "s";
    }

    /** When the player asks what happened today, hand the model today's real chat log to summarize. */
    private void appendIfTodaySummaryRequested(StringBuilder sb, String message) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isChatSummaryEnabled()) return;
        String lower = message.toLowerCase(Locale.ROOT);
        boolean asksToday = containsAny(lower, "today", "امروز");
        boolean asksWhatHappened = containsAny(lower, "happened", "چه اتفاقی", "چه خبر");
        if (!(asksToday && asksWhatHappened)) return;

        String log = plugin.getChatActivityLogger().getTodayLogText(cfg.getChatSummaryMaxCharsInjected());
        if (log.isBlank()) {
            sb.append("\nToday's chat log: (nothing logged yet today)\n");
        } else {
            sb.append("\n--- Today's public chat log (summarize this, don't invent anything beyond it) ---\n")
                    .append(log).append('\n');
        }
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
