package com.verityai.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired whenever Verity actually dispatches a real in-game command on behalf
 * of a player (see commands.enabled in config.yml). Purely informational —
 * by the time this fires the command has already been dispatched — useful
 * for other plugins that want to log or react to AI-triggered actions.
 */
public class VerityCommandExecutedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String command;
    private final boolean ranAsConsole;

    public VerityCommandExecutedEvent(Player player, String command, boolean ranAsConsole) {
        this.player = player;
        this.command = command;
        this.ranAsConsole = ranAsConsole;
    }

    public Player getPlayer() { return player; }
    public String getCommand() { return command; }
    public boolean ranAsConsole() { return ranAsConsole; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
