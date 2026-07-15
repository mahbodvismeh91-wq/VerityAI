package com.verityai.plugin.listener;

import com.verityai.plugin.VerityAI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Clears a player's short-term conversation (and conversation-mode state)
 * as soon as they disconnect — there's no point holding onto in-RAM chat
 * history for someone who isn't there to continue the conversation, and it
 * keeps memory use bounded on servers with a lot of player churn.
 *
 * This does NOT touch long-term memory (the persisted facts store) — that's
 * meant to survive across sessions, so only /verity clear removes it.
 */
public class PlayerQuitListener implements Listener {

    private final VerityAI plugin;

    public PlayerQuitListener(VerityAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getConversationManager().clear(event.getPlayer().getUniqueId());
    }
}
