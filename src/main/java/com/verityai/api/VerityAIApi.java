package com.verityai.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Public API for other plugins to integrate with VerityAI. Obtain an
 * instance via Bukkit's ServicesManager:
 *
 * <pre>
 *   RegisteredServiceProvider&lt;VerityAIApi&gt; provider =
 *       Bukkit.getServicesManager().getRegistration(VerityAIApi.class);
 *   if (provider != null) {
 *       VerityAIApi verity = provider.getProvider();
 *       verity.ask(player, "what's the weather like?", answer -> {
 *           player.sendMessage("Verity said: " + answer);
 *       });
 *   }
 * </pre>
 *
 * For deeper integration, listen for {@code com.verityai.api.events.VerityQuestionEvent},
 * {@code VerityAnswerEvent}, and {@code VerityCommandExecutedEvent} like any other Bukkit event.
 */
public interface VerityAIApi {

    /** Whether Verity is currently enabled (see /verity toggle). */
    boolean isEnabled();

    /**
     * Asks Verity a question on behalf of a player, going through the exact
     * same pipeline (moderation, rate limits, memory, function calling) as a
     * normal in-chat question — the answer is also delivered to the player's
     * chat as usual. {@code onAnswer} additionally receives the final answer
     * text once ready (called on the main thread), or is never called if the
     * request is blocked/fails silently to the player.
     */
    void ask(Player player, String question, Consumer<String> onAnswer);

    /** The long-term memory facts currently stored for a player. */
    List<String> getFacts(UUID playerUuid);

    /** Adds a fact to a player's long-term memory (subject to the same dedup/cap rules as normal). */
    void addFact(UUID playerUuid, String fact);

    /** The currently active personality preset name. */
    String getActivePersonality();

    /** The currently configured primary AI model (before any per-permission overrides). */
    String getModel();
}
