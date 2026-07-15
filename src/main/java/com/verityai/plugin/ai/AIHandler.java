package com.verityai.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.verityai.api.events.VerityAnswerEvent;
import com.verityai.api.events.VerityQuestionEvent;
import com.verityai.plugin.VerityAI;
import com.verityai.plugin.memory.ConversationManager;
import com.verityai.plugin.util.ColorUtil;
import com.verityai.plugin.util.ProfanityFilter;
import com.verityai.plugin.util.RateLimiter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level entry point used by the chat listener, commands, and the public
 * API: takes a player's question, applies moderation/rate limiting, builds
 * the full message list (system prompt + short-term memory + question),
 * calls the AI (streamed, blocking, or via function-calling) and delivers
 * the final answer back to chat.
 */
public class AIHandler {

    private static final Pattern REMEMBER_TAG = Pattern.compile("\\[REMEMBER:\\s*(.+?)\\s*]", Pattern.CASE_INSENSITIVE);

    private final VerityAI plugin;
    private final OpenRouterClient client;
    private final ProfanityFilter profanityFilter;
    private final FunctionCallService functionCallService;

    public AIHandler(VerityAI plugin) {
        this.plugin = plugin;
        this.client = new OpenRouterClient(plugin);
        this.profanityFilter = new ProfanityFilter(plugin);
        this.functionCallService = new FunctionCallService(plugin);
    }

    public OpenRouterClient getClient() { return client; }

    public void ask(Player player, String question) {
        ask(player, question, null);
    }

    /** Same as {@link #ask(Player, String)}, but also notifies {@code onAnswer} with the final text (main thread). */
    public void ask(Player player, String question, Consumer<String> onAnswer) {
        // Callers may invoke this from an async context (e.g. Paper's AsyncChatEvent runs
        // off the main thread) — but Bukkit events like VerityQuestionEvent, and the
        // rate-limiter/config reads below, all expect the main thread. Hop once here so
        // every caller (chat listener, commands, the public API) is safe by default.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> ask(player, question, onAnswer));
            return;
        }

        var cfg = plugin.getConfigManager();
        RateLimiter limiter = plugin.getRateLimiter();
        UUID uuid = player.getUniqueId();

        if (!cfg.isAiEnabled()) {
            reply(player, "Verity is currently disabled by an admin.");
            return;
        }

        if (profanityFilter.isBlocked(question)) {
            reply(player, "I can't help with that request.");
            return;
        }

        RateLimiter.Result check = limiter.check(player);
        switch (check) {
            case BUSY -> { reply(player, "I'm still answering your last question, hang on!"); return; }
            case ON_COOLDOWN -> { reply(player, "Please wait a moment before asking again."); return; }
            case RATE_LIMITED -> { reply(player, "You're asking too fast — slow down a little."); return; }
            case TOKEN_LIMIT -> { reply(player, "You've reached your daily question limit."); return; }
            case SERVER_BUSY -> { reply(player, "Verity is handling a lot of requests right now — try again in a moment."); return; }
            case OK -> { /* continue */ }
        }

        // Plugin hook: let other plugins veto or rewrite the question before it's processed.
        VerityQuestionEvent questionEvent = new VerityQuestionEvent(player, question);
        Bukkit.getPluginManager().callEvent(questionEvent);
        if (questionEvent.isCancelled()) {
            return;
        }
        String finalQuestion = questionEvent.getQuestion();

        limiter.markStarted(uuid);
        plugin.getConversationManager().addUserMessage(uuid, finalQuestion);
        plugin.getConversationManager().refreshConversationWindow(uuid);

        sendThinkingIndicator(player);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long start = System.currentTimeMillis();
            try {
                String systemPrompt = plugin.getContextBuilder().buildSystemPrompt(player, finalQuestion);
                JsonArray messages = buildMessages(systemPrompt, uuid, finalQuestion);
                String effectiveModel = cfg.getEffectiveModel(player);

                OpenRouterClient.AiResult result;
                if (cfg.isFunctionCallingEnabled()) {
                    JsonArray tools = functionCallService.buildToolSchemas(player);
                    result = client.completeWithFunctions(messages, effectiveModel, tools,
                            (name, argsJson) -> functionCallService.execute(player, name, argsJson),
                            cfg.getMaxFunctionCallRounds());
                } else {
                    result = client.complete(messages, cfg.isStreamEnabled(), effectiveModel,
                            chunk -> updateTypingActionBar(player, chunk));
                }

                String answer = result.content();
                if (profanityFilter.isBlocked(answer)) {
                    answer = "Sorry, I can't share that.";
                }

                answer = extractAndSaveMemories(uuid, answer);

                var cmdResult = plugin.getAiCommandExecutor().process(player, answer);
                answer = cmdResult.cleanedText();
                if (!cmdResult.executedCommands().isEmpty()) {
                    notifyCommandsRan(player, cmdResult.executedCommands());
                    for (int i = 0; i < cmdResult.executedCommands().size(); i++) {
                        plugin.getStatsService().recordCommandExecuted();
                    }
                }

                long duration = System.currentTimeMillis() - start;

                // Plugin hook: let other plugins rewrite the final answer before it's shown/stored.
                VerityAnswerEvent answerEvent = new VerityAnswerEvent(player, finalQuestion, answer, result.modelUsed());
                Bukkit.getPluginManager().callEvent(answerEvent);
                answer = answerEvent.getAnswer();

                plugin.getConversationManager().addAssistantMessage(uuid, answer);
                maybeCompressCondensedSummary(uuid);
                limiter.addTokensUsed(uuid, result.totalTokens());
                plugin.getStatsService().recordSuccess(player.getName(), result.modelUsed(), duration, result.totalTokens());
                plugin.getDebugLogger().requestLog(player.getName(), finalQuestion, result.modelUsed(), duration);
                plugin.getHistoryLogger().log(uuid, player.getName(), finalQuestion, answer);

                deliverAnswer(player, answer, result.modelUsed(), duration);
                if (onAnswer != null) {
                    String finalAnswer = answer;
                    Bukkit.getScheduler().runTask(plugin, () -> onAnswer.accept(finalAnswer));
                }
            } catch (Exception e) {
                plugin.getStatsService().recordFailure();
                plugin.getLogger().log(Level.WARNING, "VerityAI: AI request failed for " + player.getName(), e);
                plugin.getDebugLogger().errorLog("ask(" + player.getName() + ")", e);
                reply(player, "Sorry, I couldn't reach my brain right now. Please try again later.");
            } finally {
                limiter.markFinished(uuid);
            }
        });
    }

    /** Pulls [REMEMBER: fact] tags out of the AI's answer, saves each fact, and returns the cleaned text. */
    private String extractAndSaveMemories(UUID uuid, String answer) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isLongTermEnabled() || !cfg.isAutoRememberEnabled() || answer == null) {
            return answer;
        }

        Matcher matcher = REMEMBER_TAG.matcher(answer);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            if (raw.isEmpty()) continue;
            int pipeIdx = raw.indexOf('|');
            if (pipeIdx > 0) {
                String category = raw.substring(0, pipeIdx).trim();
                String fact = raw.substring(pipeIdx + 1).trim();
                if (!fact.isEmpty()) {
                    plugin.getLongTermMemoryStore().addCategorizedFact(uuid, category, fact);
                }
            } else {
                plugin.getLongTermMemoryStore().addCategorizedFact(uuid, "general", raw);
            }
        }
        return matcher.replaceAll("").stripTrailing();
    }

    /**
     * Once the naive concatenated "condensed summary" of dropped turns gets close to its
     * size cap, replace it with a real AI-generated compression (2-3 sentences) instead of
     * just letting it keep truncating from the front — a proper "auto-summarize the
     * conversation" upgrade, run in the background so it never delays the current reply.
     */
    private void maybeCompressCondensedSummary(UUID uuid) {
        var cfg = plugin.getConfigManager();
        String condensed = plugin.getConversationManager().getCondensedSummary(uuid);
        if (condensed.isBlank() || condensed.length() < cfg.getSummaryMaxChars() * 0.9) {
            return; // not worth a paid API call yet
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonArray messages = new JsonArray();
                JsonObject system = new JsonObject();
                system.addProperty("role", "system");
                system.addProperty("content", "Summarize the following conversation notes into 2-3 concise "
                        + "sentences. Preserve names, preferences, and any important facts. Output only the summary.\n\n"
                        + condensed);
                messages.add(system);

                OpenRouterClient.AiResult result = client.complete(messages, false, null);
                if (result.content() != null && !result.content().isBlank()) {
                    plugin.getConversationManager().setCondensedSummary(uuid, result.content());
                }
            } catch (Exception e) {
                plugin.getDebugLogger().debug("VerityAI: condensed-summary compression failed: " + e.getMessage());
                // Leave the naive concatenation in place — not a hard failure.
            }
        });
    }

    private void notifyCommandsRan(Player player, List<String> commands) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            for (String command : commands) {
                player.sendMessage(Component.text("▶ Verity ran: /" + command, NamedTextColor.DARK_AQUA));
            }
        });
    }

    private JsonArray buildMessages(String systemPrompt, UUID uuid, String question) {
        var cfg = plugin.getConfigManager();
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);

        String condensed = plugin.getConversationManager().getCondensedSummary(uuid);
        if (!condensed.isBlank()) {
            JsonObject summaryNote = new JsonObject();
            summaryNote.addProperty("role", "system");
            summaryNote.addProperty("content", "--- Earlier in this conversation (condensed) ---\n" + condensed);
            messages.add(summaryNote);
        }

        // Enforce a rough character budget on the resent history so one very long
        // stretch of turns can't balloon the request — trims oldest-first.
        int budget = cfg.getContextMaxChars() - systemPrompt.length() - condensed.length() - question.length();
        Deque<ConversationManager.Turn> history = plugin.getConversationManager().getHistory(uuid);
        java.util.List<ConversationManager.Turn> included = new java.util.ArrayList<>();
        int used = 0;
        // Walk from most recent backwards so we always keep the freshest turns if we must cut.
        for (ConversationManager.Turn turn : (Iterable<ConversationManager.Turn>) history::descendingIterator) {
            int cost = turn.content().length();
            if (budget > 0 && used + cost > budget && !included.isEmpty()) {
                break;
            }
            included.add(0, turn);
            used += cost;
        }

        for (ConversationManager.Turn turn : included) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", turn.role());
            msg.addProperty("content", turn.content());
            messages.add(msg);
        }

        return messages;
    }

    private void sendThinkingIndicator(Player player) {
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendActionBar(ColorUtil.color(plugin.getConfigManager().getThinkingMessage())));
    }

    private void updateTypingActionBar(Player player, String chunk) {
        // Cheap "typing" effect: keep the action bar alive while streaming.
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendActionBar(ColorUtil.color(plugin.getConfigManager().getThinkingMessage())));
    }

    private void deliverAnswer(Player player, String answer, String modelUsed, long durationMs) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            Component prefix = ColorUtil.color(plugin.getConfigManager().getRawPrefix());
            // Always tag whoever asked the question, so it's clear who Verity is replying to.
            Component askerTag = Component.text("@" + player.getName() + " ", NamedTextColor.GOLD);
            Component message = prefix.append(askerTag).append(mentionify(answer, player));
            player.sendMessage(message);
            pingMention(player, player);

            if (plugin.getConfigManager().isDebugEnabled()) {
                player.sendMessage(Component.text(
                        "[debug] model=" + modelUsed + " time=" + durationMs + "ms",
                        NamedTextColor.DARK_GRAY));
            }
        });
    }

    /** Highlights "@PlayerName" mentions of currently online players inside the AI's reply, and pings them. */
    private Component mentionify(String text, Player asker) {
        Component result = Component.empty();
        String[] words = text.split(" ", -1);
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            String bare = word.replaceAll("[^a-zA-Z0-9_]", "");
            Player mentioned = bare.isBlank() ? null : Bukkit.getPlayerExact(bare.startsWith("@") ? bare.substring(1) : bare);
            if (word.startsWith("@") && mentioned != null) {
                result = result.append(Component.text(word, NamedTextColor.GOLD));
                if (!mentioned.getUniqueId().equals(asker.getUniqueId())) {
                    pingMention(asker, mentioned);
                }
            } else {
                result = result.append(Component.text(word));
            }
            if (i < words.length - 1) {
                result = result.append(Component.text(" "));
            }
        }
        return result;
    }

    /** Plays a subtle "you were mentioned" sound to whoever Verity's reply is tagging/addressing. */
    private void pingMention(Player asker, Player mentioned) {
        if (!mentioned.isOnline()) return;
        try {
            mentioned.playSound(mentioned.getLocation(),
                    org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
        } catch (Throwable ignored) {
            // Never let a cosmetic sound failure affect message delivery.
        }
    }

    private void reply(Player player, String text) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Component prefix = ColorUtil.color(plugin.getConfigManager().getRawPrefix());
            player.sendMessage(prefix.append(Component.text(text)));
        });
    }
}
