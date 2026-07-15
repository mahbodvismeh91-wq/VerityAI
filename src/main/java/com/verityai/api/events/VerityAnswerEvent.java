package com.verityai.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after Verity's answer has been generated (and moderated/memory-tagged/
 * command-tagged) but before it's sent to the player. Other plugins can call
 * {@link #setAnswer(String)} to change what's actually shown in chat — e.g. to
 * add a plugin-specific note, translate it, or log it elsewhere.
 */
public class VerityAnswerEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String question;
    private String answer;
    private final String modelUsed;

    public VerityAnswerEvent(Player player, String question, String answer, String modelUsed) {
        this.player = player;
        this.question = question;
        this.answer = answer;
        this.modelUsed = modelUsed;
    }

    public Player getPlayer() { return player; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getModelUsed() { return modelUsed; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
