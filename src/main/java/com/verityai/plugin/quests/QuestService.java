package com.verityai.plugin.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.verityai.plugin.VerityAI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand quest suggestions ("collect 32 iron today") generated from the
 * player's live context (location, held item, known interests/playstyle if
 * long-term memory has any). Intentionally simple: one active quest per
 * player, text-only, in-memory only (not persisted — regenerate with
 * /verity quest if the server restarts). There's no automatic progress
 * tracking (e.g. counting iron collected) — that would need hooking every
 * relevant inventory/block event, which is a bigger feature on its own.
 */
public class QuestService {

    private final VerityAI plugin;
    private final Map<UUID, String> currentQuest = new ConcurrentHashMap<>();

    public QuestService(VerityAI plugin) {
        this.plugin = plugin;
    }

    public String getCurrentQuest(UUID uuid) {
        return currentQuest.getOrDefault(uuid, "");
    }

    public void clearQuest(UUID uuid) {
        currentQuest.remove(uuid);
    }

    /** Generates a new quest asynchronously and messages the player once ready. */
    public void generate(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            var item = player.getInventory().getItemInMainHand();
            String heldItem = (item == null || item.getType().isAir()) ? "nothing" : item.getType().name();
            var loc = player.getLocation();
            String context = String.format("world=%s, x=%d, y=%d, z=%d, held item=%s",
                    player.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), heldItem);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    JsonArray messages = new JsonArray();
                    JsonObject system = new JsonObject();
                    system.addProperty("role", "system");
                    system.addProperty("content", "Generate ONE short, fun, achievable Minecraft quest/objective "
                            + "for a player with this live context: " + context + ". Keep it to one or two sentences, "
                            + "concrete and specific (e.g. a quantity, a place, or a target), no preamble.");
                    messages.add(system);

                    var result = plugin.getAIHandler().getClient().complete(messages, false, null);
                    String quest = result.content() == null || result.content().isBlank()
                            ? "Explore somewhere you haven't been yet!" : result.content().trim();
                    currentQuest.put(uuid, quest);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(Component.text("📜 New quest: ", NamedTextColor.GOLD)
                                    .append(Component.text(quest)));
                        }
                    });
                } catch (Exception e) {
                    plugin.getDebugLogger().debug("VerityAI: quest generation failed: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(Component.text("Couldn't come up with a quest right now — try again in a bit.",
                                    NamedTextColor.RED));
                        }
                    });
                }
            });
        });
    }
}
