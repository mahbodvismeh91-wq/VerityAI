package com.verityai.plugin.listener;

import com.verityai.plugin.VerityAI;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Verity reacting to notable game moments — player death/join, defeating a
 * boss mob, and (where the server-mapped API exposes them) raids. Reactions
 * are canned, config-editable templates rather than live AI calls: an event
 * reaction has to be instant and free, and a template with a couple of
 * placeholders reads just as naturally for these short moments.
 */
public class GameEventListener implements Listener {

    private static final Set<EntityType> BOSS_TYPES = Set.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);

    private final VerityAI plugin;

    public GameEventListener(VerityAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEventReactionsEnabled()) return;
        sendTemplate(event.getPlayer(), cfg.getEventReactionMessage("on-join"),
                mapOf("player", event.getPlayer().getName()));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEventReactionsEnabled()) return;
        Player player = event.getEntity();
        String cause = event.getDeathMessage() != null ? "an unfortunate event" : "something";
        sendTemplate(player, cfg.getEventReactionMessage("on-death"),
                mapOf("player", player.getName(), "cause", cause));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEventReactionsEnabled() || !BOSS_TYPES.contains(event.getEntityType())) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        String bossName = event.getEntityType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        sendTemplate(killer, cfg.getEventReactionMessage("on-boss-defeat"),
                mapOf("player", killer.getName(), "boss", bossName));
    }

    @EventHandler
    public void onRaidTrigger(org.bukkit.event.raid.RaidTriggerEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEventReactionsEnabled()) return;
        broadcastToWorld(event.getWorld(), cfg.getEventReactionMessage("on-raid-start"),
                mapOf("world", event.getWorld().getName()));
    }

    @EventHandler
    public void onRaidFinish(org.bukkit.event.raid.RaidFinishEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEventReactionsEnabled()) return;
        broadcastToWorld(event.getWorld(), cfg.getEventReactionMessage("on-raid-victory"),
                mapOf("world", event.getWorld().getName()));
    }

    private void broadcastToWorld(org.bukkit.World world, String template, java.util.Map<String, String> vars) {
        if (template == null || template.isBlank()) return;
        for (Player player : world.getPlayers()) {
            sendTemplate(player, template, vars);
        }
    }

    private void sendTemplate(Player player, String template, java.util.Map<String, String> vars) {
        if (template == null || template.isBlank() || !player.isOnline()) return;
        String text = template;
        for (var entry : vars.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        Component prefix = com.verityai.plugin.util.ColorUtil.color(plugin.getConfigManager().getRawPrefix());
        player.sendMessage(prefix.append(Component.text(text)));
    }

    private java.util.Map<String, String> mapOf(String... kv) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
