package com.verityai.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired just before Verity processes a player's question (after rate-limit/
 * moderation checks pass, before the AI is called). Other plugins can:
 *  - cancel it to silently stop Verity from answering
 *  - call {@link #setQuestion(String)} to rewrite what's actually sent to the AI
 */
public class VerityQuestionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private String question;
    private boolean cancelled;

    public VerityQuestionEvent(Player player, String question) {
        this.player = player;
        this.question = question;
    }

    public Player getPlayer() { return player; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
