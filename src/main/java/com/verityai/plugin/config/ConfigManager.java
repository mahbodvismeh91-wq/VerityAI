package com.verityai.plugin.config;

import com.verityai.plugin.VerityAI;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central place for every setting in config.yml. Call {@link #load()} again
 * to hot-reload (used by /verity reload).
 */
public class ConfigManager {

    private final VerityAI plugin;

    // ai.*
    private String apiUrl;
    private List<String> apiKeys;
    private String model;
    private List<String> fallbackModels;
    private int maxTokens;
    private double temperature;
    private boolean streamEnabled;
    private String siteUrl;
    private String siteName;
    private int connectTimeoutSeconds;
    private int requestTimeoutSeconds;
    private int maxRetriesPerKey;

    // ai.function-calling.*
    private boolean functionCallingEnabled;
    private int maxFunctionCallRounds;

    // ai.embeddings.*
    private boolean embeddingsEnabled;
    private String embeddingsApiUrl;
    private String embeddingsModel;
    private double embeddingsSimilarityThreshold;

    // personality.*
    private String activePersonality;
    private final Map<String, String> personalityPresets = new LinkedHashMap<>();
    private final Map<String, String> personalityPerWorld = new LinkedHashMap<>();

    // ai.custom-knowledge (config-taught facts/instructions)
    private List<String> customKnowledge;

    // server-info.*
    private String serverName;
    private String serverRules;
    private String serverIp;
    private String serverWebsite;
    private String serverGamemodes;
    private String serverVersion;
    private String ownerName;

    // chat.*
    private String prefix;
    private String trigger;
    private String thinkingMessage;
    private String language;
    private long conversationTimeoutSeconds;

    // memory.*
    private int shortTermLimit;
    private boolean longTermEnabled;
    private int longTermMaxEntries;
    private boolean autoRememberEnabled;
    private boolean saveHistory;
    private int conversationIdleClearMinutes;
    private int longTermAutosaveMinutes;
    private int contextMaxChars;
    private int summaryMaxChars;
    private int historyRetentionDays;
    private int relevantFactsLimit;
    private int maxTasksPerPlayer;

    // event-reactions.*
    private boolean eventReactionsEnabled;
    private final Map<String, String> eventReactionMessages = new LinkedHashMap<>();

    // chat-summary.*
    private boolean chatSummaryEnabled;
    private int chatSummaryMaxLines;
    private int chatSummaryMaxCharsInjected;
    private int maxLessons;

    // limits.*
    private long cooldownSeconds;
    private int maxRequestsPerMinute;
    private int maxTokensPerDay;
    private int globalMaxRequestsPerMinute;
    private int maxConcurrentRequests;

    // moderation.*
    private boolean profanityFilterEnabled;
    private List<String> blockedWords;

    // integrations.*
    private boolean placeholderApiEnabled;
    private boolean vaultEnabled;
    private boolean luckPermsEnabled;
    private boolean essentialsXEnabled;
    private String mapWebUrl;

    // commands.* (Verity executing real game commands when a player asks)
    private boolean commandsEnabled;
    private String commandRunAs;
    private List<String> commandWhitelist;
    private List<String> ownerCommandWhitelist;

    // debug.*
    private boolean debugEnabled;
    private boolean logRequests;
    private boolean logErrors;

    // permissions.tiers.* — checked in order, first matching permission node wins
    /** One override tier: if the player has `permission`, these non-null fields override the global defaults. */
    public record PermissionTier(String permission, String model, Long cooldownSeconds,
                                  Integer maxRequestsPerMinute, Integer maxTokensPerDay) {}
    private final List<PermissionTier> permissionTiers = new ArrayList<>();

    // runtime toggle (not persisted, changes with /verity toggle)
    private boolean aiEnabled = true;

    public ConfigManager(VerityAI plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        this.apiUrl = c.getString("ai.api-url", "https://openrouter.ai/api/v1/chat/completions");
        this.apiKeys = c.getStringList("ai.api-keys");
        this.model = c.getString("ai.model", "nvidia/nemotron-3-ultra-550b-a55b:free");
        this.fallbackModels = c.getStringList("ai.fallback-models");
        this.maxTokens = c.getInt("ai.max-tokens", 500);
        this.temperature = c.getDouble("ai.temperature", 0.7);
        this.streamEnabled = c.getBoolean("ai.stream", true);
        this.siteUrl = c.getString("ai.site-url", "");
        this.siteName = c.getString("ai.site-name", "VerityAI");
        this.customKnowledge = c.getStringList("ai.custom-knowledge");
        this.connectTimeoutSeconds = positiveOrDefault(c.getInt("ai.connect-timeout-seconds", 10), 10);
        this.requestTimeoutSeconds = positiveOrDefault(c.getInt("ai.request-timeout-seconds", 45), 45);
        this.maxRetriesPerKey = Math.max(0, c.getInt("ai.max-retries-per-key", 2));

        this.functionCallingEnabled = c.getBoolean("ai.function-calling.enabled", false);
        this.maxFunctionCallRounds = positiveOrDefault(c.getInt("ai.function-calling.max-rounds", 3), 3);

        this.embeddingsEnabled = c.getBoolean("ai.embeddings.enabled", false);
        this.embeddingsApiUrl = c.getString("ai.embeddings.api-url", "https://openrouter.ai/api/v1/embeddings");
        this.embeddingsModel = c.getString("ai.embeddings.model", "openai/text-embedding-3-small");
        this.embeddingsSimilarityThreshold = c.getDouble("ai.embeddings.similarity-threshold", 0.88);

        this.activePersonality = c.getString("personality.active", "default");
        personalityPresets.clear();
        ConfigurationSection presets = c.getConfigurationSection("personality.presets");
        if (presets != null) {
            for (String key : presets.getKeys(false)) {
                personalityPresets.put(key, presets.getString(key, ""));
            }
        }
        if (personalityPresets.isEmpty()) {
            personalityPresets.put("default",
                    "You are Verity, a friendly Minecraft AI assistant. Help players with Minecraft questions.");
        }

        personalityPerWorld.clear();
        ConfigurationSection perWorld = c.getConfigurationSection("personality.per-world");
        if (perWorld != null) {
            for (String worldName : perWorld.getKeys(false)) {
                personalityPerWorld.put(worldName, perWorld.getString(worldName, ""));
            }
        }

        this.serverName = c.getString("server-info.name", "");
        this.serverRules = c.getString("server-info.rules", "");
        this.serverIp = c.getString("server-info.ip", "");
        this.serverWebsite = c.getString("server-info.website", "");
        this.serverGamemodes = c.getString("server-info.gamemodes", "");
        this.serverVersion = c.getString("server-info.version", "1.21.8");
        this.ownerName = c.getString("server-info.owner", "");

        this.prefix = c.getString("chat.prefix", "&b[Verity]&r ");
        this.trigger = c.getString("chat.trigger", "@verity");
        this.thinkingMessage = c.getString("chat.thinking-message", "&7Verity is thinking...");
        this.language = c.getString("chat.language", "auto");
        this.conversationTimeoutSeconds = c.getLong("chat.conversation-timeout-seconds", 60);

        this.shortTermLimit = positiveOrDefault(c.getInt("memory.short-term-limit", 10), 10);
        this.longTermEnabled = c.getBoolean("memory.long-term-enabled", false);
        this.longTermMaxEntries = positiveOrDefault(c.getInt("memory.long-term-max-entries", 100), 100);
        this.autoRememberEnabled = c.getBoolean("memory.auto-remember", true);
        this.saveHistory = c.getBoolean("memory.save-history", true);
        this.conversationIdleClearMinutes = Math.max(0, c.getInt("memory.idle-clear-minutes", 30));
        this.longTermAutosaveMinutes = positiveOrDefault(c.getInt("memory.autosave-interval-minutes", 5), 5);
        this.contextMaxChars = positiveOrDefault(c.getInt("memory.max-context-chars", 8000), 8000);
        this.summaryMaxChars = positiveOrDefault(c.getInt("memory.summary-max-chars", 800), 800);
        this.historyRetentionDays = Math.max(0, c.getInt("memory.history-retention-days", 30));
        this.relevantFactsLimit = positiveOrDefault(c.getInt("memory.relevant-facts-limit", 8), 8);
        this.maxTasksPerPlayer = positiveOrDefault(c.getInt("tasks.max-per-player", 10), 10);

        this.eventReactionsEnabled = c.getBoolean("event-reactions.enabled", true);
        eventReactionMessages.clear();
        ConfigurationSection reactions = c.getConfigurationSection("event-reactions.messages");
        if (reactions != null) {
            for (String key : reactions.getKeys(false)) {
                eventReactionMessages.put(key, reactions.getString(key, ""));
            }
        }

        this.chatSummaryEnabled = c.getBoolean("chat-summary.enabled", true);
        this.chatSummaryMaxLines = positiveOrDefault(c.getInt("chat-summary.max-lines", 300), 300);
        this.chatSummaryMaxCharsInjected = positiveOrDefault(c.getInt("chat-summary.max-chars-injected", 3000), 3000);
        this.maxLessons = positiveOrDefault(c.getInt("lessons.max-entries", 30), 30);

        this.cooldownSeconds = Math.max(0, c.getLong("limits.cooldown-seconds", 5));
        this.maxRequestsPerMinute = Math.max(0, c.getInt("limits.max-requests-per-minute", 6));
        this.maxTokensPerDay = Math.max(0, c.getInt("limits.max-tokens-per-day", 20000));
        this.globalMaxRequestsPerMinute = Math.max(0, c.getInt("limits.global-max-requests-per-minute", 60));
        this.maxConcurrentRequests = positiveOrDefault(c.getInt("limits.max-concurrent-requests", 10), 10);

        this.profanityFilterEnabled = c.getBoolean("moderation.profanity-filter", true);
        this.blockedWords = c.getStringList("moderation.blocked-words");

        this.placeholderApiEnabled = c.getBoolean("integrations.placeholderapi", true);
        this.vaultEnabled = c.getBoolean("integrations.vault", true);
        this.luckPermsEnabled = c.getBoolean("integrations.luckperms", true);
        this.essentialsXEnabled = c.getBoolean("integrations.essentialsx", true);
        this.mapWebUrl = c.getString("integrations.map-web-url", "");

        this.commandsEnabled = c.getBoolean("commands.enabled", false);
        this.commandRunAs = c.getString("commands.run-as", "player");
        this.commandWhitelist = c.getStringList("commands.whitelist");
        this.ownerCommandWhitelist = c.getStringList("commands.owner-only-console-whitelist");

        this.debugEnabled = c.getBoolean("debug.enabled", false);
        this.logRequests = c.getBoolean("debug.log-requests", true);
        this.logErrors = c.getBoolean("debug.log-errors", true);

        permissionTiers.clear();
        List<Map<?, ?>> tiers = c.getMapList("permissions.tiers");
        for (Map<?, ?> tier : tiers) {
            Object permObj = tier.get("permission");
            if (permObj == null) continue;
            String permission = permObj.toString();
            String tierModel = tier.get("model") != null ? tier.get("model").toString() : null;
            Long tierCooldown = tier.get("cooldown-seconds") != null ? Long.valueOf(tier.get("cooldown-seconds").toString()) : null;
            Integer tierMaxPerMinute = tier.get("max-requests-per-minute") != null ? Integer.valueOf(tier.get("max-requests-per-minute").toString()) : null;
            Integer tierMaxTokens = tier.get("max-tokens-per-day") != null ? Integer.valueOf(tier.get("max-tokens-per-day").toString()) : null;
            permissionTiers.add(new PermissionTier(permission, tierModel, tierCooldown, tierMaxPerMinute, tierMaxTokens));
        }

        if (apiKeys == null || apiKeys.isEmpty() || apiKeys.get(0).contains("YOUR_")) {
            plugin.getLogger().warning("VerityAI: no valid OpenRouter API key set in config.yml!");
        }
    }

    /** Validation helper: falls back to a safe default when a config value is missing/non-positive. */
    private int positiveOrDefault(int value, int fallback) {
        if (value <= 0) {
            plugin.getLogger().warning("VerityAI: invalid config value (" + value + "), using default " + fallback + " instead.");
            return fallback;
        }
        return value;
    }

    public String getActivePersonalityPrompt() {
        return personalityPresets.getOrDefault(activePersonality,
                "You are Verity, a friendly Minecraft AI assistant.");
    }

    public boolean setActivePersonality(String name) {
        if (!personalityPresets.containsKey(name)) {
            return false;
        }
        this.activePersonality = name;
        return true;
    }

    public List<String> getPersonalityNames() {
        return new ArrayList<>(personalityPresets.keySet());
    }

    // ------- getters -------
    public String getApiUrl() { return apiUrl; }
    public List<String> getApiKeys() { return apiKeys; }
    public String getModel() { return model; }

    /** Sets and persists the primary AI model to config.yml (used by /verity model). */
    public void setModel(String newModel) {
        this.model = newModel;
        plugin.getConfig().set("ai.model", newModel);
        plugin.saveConfig();
    }
    public List<String> getFallbackModels() { return fallbackModels; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }
    public boolean isStreamEnabled() { return streamEnabled; }
    public String getSiteUrl() { return siteUrl; }
    public String getSiteName() { return siteName; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getMaxRetriesPerKey() { return maxRetriesPerKey; }

    public boolean isFunctionCallingEnabled() { return functionCallingEnabled; }
    public int getMaxFunctionCallRounds() { return maxFunctionCallRounds; }

    public boolean isEmbeddingsEnabled() { return embeddingsEnabled; }
    public String getEmbeddingsApiUrl() { return embeddingsApiUrl; }
    public String getEmbeddingsModel() { return embeddingsModel; }
    public double getEmbeddingsSimilarityThreshold() { return embeddingsSimilarityThreshold; }

    /** Returns the personality prompt for this world if one is mapped, otherwise the globally active one. */
    public String getPersonalityPromptForWorld(String worldName) {
        String presetName = personalityPerWorld.get(worldName);
        if (presetName != null && personalityPresets.containsKey(presetName)) {
            return personalityPresets.get(presetName);
        }
        return getActivePersonalityPrompt();
    }

    /** First matching permission tier (in config order) wins; null fields fall back to the global default. */
    private PermissionTier matchingTier(Player player) {
        for (PermissionTier tier : permissionTiers) {
            if (player.hasPermission(tier.permission())) {
                return tier;
            }
        }
        return null;
    }

    public String getEffectiveModel(Player player) {
        PermissionTier tier = matchingTier(player);
        return (tier != null && tier.model() != null && !tier.model().isBlank()) ? tier.model() : model;
    }

    public long getEffectiveCooldownSeconds(Player player) {
        PermissionTier tier = matchingTier(player);
        return (tier != null && tier.cooldownSeconds() != null) ? tier.cooldownSeconds() : cooldownSeconds;
    }

    public int getEffectiveMaxRequestsPerMinute(Player player) {
        PermissionTier tier = matchingTier(player);
        return (tier != null && tier.maxRequestsPerMinute() != null) ? tier.maxRequestsPerMinute() : maxRequestsPerMinute;
    }

    public int getEffectiveMaxTokensPerDay(Player player) {
        PermissionTier tier = matchingTier(player);
        return (tier != null && tier.maxTokensPerDay() != null) ? tier.maxTokensPerDay() : maxTokensPerDay;
    }

    public String getActivePersonality() { return activePersonality; }

    public String getServerName() { return serverName; }
    public String getServerRules() { return serverRules; }
    public String getServerIp() { return serverIp; }
    public String getServerWebsite() { return serverWebsite; }
    public String getServerGamemodes() { return serverGamemodes; }
    public String getServerVersion() { return serverVersion; }

    public List<String> getCustomKnowledge() { return customKnowledge; }

    public String getOwnerName() { return ownerName == null ? "" : ownerName; }

    /** Sets and persists the server owner's name to config.yml (used by /verity owner). */
    public void setOwnerName(String name) {
        this.ownerName = name == null ? "" : name;
        plugin.getConfig().set("server-info.owner", this.ownerName);
        plugin.saveConfig();
    }

    public boolean isOwner(String playerName) {
        return playerName != null && !getOwnerName().isBlank() && getOwnerName().equalsIgnoreCase(playerName);
    }

    public String getMapWebUrl() { return mapWebUrl; }

    public boolean isCommandsEnabled() { return commandsEnabled; }
    public String getCommandRunAs() { return commandRunAs; }
    public List<String> getCommandWhitelist() { return commandWhitelist; }
    public List<String> getOwnerCommandWhitelist() { return ownerCommandWhitelist; }

    public boolean isAutoRememberEnabled() { return autoRememberEnabled; }

    public String getRawPrefix() { return prefix; }
    public String getTrigger() { return trigger; }
    public String getThinkingMessage() { return thinkingMessage; }
    public String getLanguage() { return language; }
    public long getConversationTimeoutSeconds() { return conversationTimeoutSeconds; }

    public int getShortTermLimit() { return shortTermLimit; }
    public boolean isLongTermEnabled() { return longTermEnabled; }
    public int getLongTermMaxEntries() { return longTermMaxEntries; }
    public boolean isSaveHistory() { return saveHistory; }
    public int getConversationIdleClearMinutes() { return conversationIdleClearMinutes; }
    public int getLongTermAutosaveMinutes() { return longTermAutosaveMinutes; }
    public int getContextMaxChars() { return contextMaxChars; }
    public int getSummaryMaxChars() { return summaryMaxChars; }
    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public int getRelevantFactsLimit() { return relevantFactsLimit; }
    public int getMaxTasksPerPlayer() { return maxTasksPerPlayer; }

    public boolean isEventReactionsEnabled() { return eventReactionsEnabled; }
    public String getEventReactionMessage(String key) { return eventReactionMessages.getOrDefault(key, ""); }

    public boolean isChatSummaryEnabled() { return chatSummaryEnabled; }
    public int getChatSummaryMaxLines() { return chatSummaryMaxLines; }
    public int getChatSummaryMaxCharsInjected() { return chatSummaryMaxCharsInjected; }
    public int getMaxLessons() { return maxLessons; }

    public long getCooldownSeconds() { return cooldownSeconds; }
    public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
    public int getMaxTokensPerDay() { return maxTokensPerDay; }
    public int getGlobalMaxRequestsPerMinute() { return globalMaxRequestsPerMinute; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }

    public boolean isProfanityFilterEnabled() { return profanityFilterEnabled; }
    public List<String> getBlockedWords() { return blockedWords; }

    public boolean isPlaceholderApiIntegrationEnabled() { return placeholderApiEnabled; }
    public boolean isVaultIntegrationEnabled() { return vaultEnabled; }
    public boolean isLuckPermsIntegrationEnabled() { return luckPermsEnabled; }
    public boolean isEssentialsXIntegrationEnabled() { return essentialsXEnabled; }

    public boolean isDebugEnabled() { return debugEnabled; }
    public void setDebugEnabled(boolean value) { this.debugEnabled = value; }
    public boolean isLogRequests() { return logRequests; }
    public boolean isLogErrors() { return logErrors; }

    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }
}
