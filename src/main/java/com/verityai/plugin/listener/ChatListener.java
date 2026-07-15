package com.verityai.plugin.listener;

import com.verityai.plugin.VerityAI;
import com.verityai.plugin.util.ColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Listens to player chat. A message is routed to Verity when either:
 *  - it starts with the configured trigger (default "@verity"), or
 *  - the player is currently in "conversation mode" (started via /verity chat),
 *    which lets them keep talking without retyping the trigger every time.
 */
public class ChatListener implements Listener {

    private final VerityAI plugin;

    public ChatListener(VerityAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String trigger = plugin.getConfigManager().getTrigger();
        Player player = event.getPlayer();

        boolean triggered = startsWithIgnoreCase(message, trigger);
        boolean conversationMode = plugin.getConversationManager().isInActiveConversation(player.getUniqueId());

        if (!triggered && !conversationMode) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("verity.use")) {
            player.sendMessage(ColorUtil.color(plugin.getConfigManager().getRawPrefix())
                    .append(net.kyori.adventure.text.Component.text("You don't have permission to talk to Verity.")));
            return;
        }

        String question = triggered ? message.substring(trigger.length()).trim() : message.trim();

        // Let players end conversation mode naturally.
        if (conversationMode && !triggered && isExitPhrase(question)) {
            plugin.getConversationManager().endConversation(player.getUniqueId());
            return;
        }

        if (question.isEmpty()) {
            return;
        }

        plugin.getAIHandler().ask(player, question);
    }

    private boolean isExitPhrase(String text) {
        String lower = text.toLowerCase();
        return lower.equals("bye") || lower.equals("stop") || lower.equals("خداحافظ") || lower.equals("تمام");
    }

    private boolean startsWithIgnoreCase(String message, String prefix) {
        return message.length() >= prefix.length()
                && message.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
