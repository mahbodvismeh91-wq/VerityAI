package com.verityai.api;

import com.verityai.plugin.VerityAI;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class VerityAIApiImpl implements VerityAIApi {

    private final VerityAI plugin;

    public VerityAIApiImpl(VerityAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfigManager().isAiEnabled();
    }

    @Override
    public void ask(Player player, String question, Consumer<String> onAnswer) {
        plugin.getAIHandler().ask(player, question, onAnswer);
    }

    @Override
    public List<String> getFacts(UUID playerUuid) {
        return plugin.getLongTermMemoryStore().getFacts(playerUuid);
    }

    @Override
    public void addFact(UUID playerUuid, String fact) {
        plugin.getLongTermMemoryStore().addFact(playerUuid, fact);
    }

    @Override
    public String getActivePersonality() {
        return plugin.getConfigManager().getActivePersonality();
    }

    @Override
    public String getModel() {
        return plugin.getConfigManager().getModel();
    }
}
