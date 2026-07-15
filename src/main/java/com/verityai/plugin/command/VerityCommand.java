package com.verityai.plugin.command;

import com.verityai.plugin.VerityAI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

public class VerityCommand implements CommandExecutor {

    private final VerityAI plugin;

    public VerityCommand(VerityAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "clear" -> handleClear(sender, args);
            case "info" -> handleInfo(sender);
            case "debug" -> handleDebug(sender);
            case "toggle", "on", "off" -> handleToggle(sender, args[0]);
            case "chat" -> handleChatMode(sender);
            case "personality" -> handlePersonality(sender, args);
            case "owner" -> handleOwner(sender, args);
            case "map" -> handleMap(sender);
            case "stats" -> handleStats(sender);
            case "model" -> handleModel(sender, args);
            case "task" -> handleTask(sender, args);
            case "quest" -> handleQuest(sender);
            case "tutorial" -> handleTutorial(sender, args);
            case "feedback" -> handleFeedback(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("verity.reload")) {
            deny(sender);
            return true;
        }
        plugin.getConfigManager().load();
        sender.sendMessage(Component.text("VerityAI configuration reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        UUID target;
        String targetName;

        if (args.length >= 2) {
            if (!sender.hasPermission("verity.clear.others")) {
                deny(sender);
                return true;
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            target = offline.getUniqueId();
            targetName = args[1];
        } else if (sender instanceof Player player) {
            target = player.getUniqueId();
            targetName = "your";
        } else {
            sender.sendMessage(Component.text("Console must specify a player: /verity clear <player>", NamedTextColor.RED));
            return true;
        }

        plugin.getConversationManager().clear(target);
        if (plugin.getConfigManager().isLongTermEnabled()) {
            plugin.getLongTermMemoryStore().clear(target);
        }
        sender.sendMessage(Component.text("Cleared " + targetName + " conversation memory.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("verity.info")) {
            deny(sender);
            return true;
        }
        var cfg = plugin.getConfigManager();
        var hooks = plugin.getHookManager();

        sender.sendMessage(Component.text("--- VerityAI ---", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Status: " + (cfg.isAiEnabled() ? "enabled" : "disabled"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Model: " + cfg.getModel(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Personality: " + cfg.getActivePersonality(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Streaming: " + cfg.isStreamEnabled(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Long-term memory: " + cfg.isLongTermEnabled()
                + " (auto-remember: " + cfg.isAutoRememberEnabled() + ")", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Owner: " + (cfg.getOwnerName().isBlank() ? "not set" : cfg.getOwnerName()),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Command execution: " + cfg.isCommandsEnabled(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Function calling: " + cfg.isFunctionCallingEnabled()
                + " | Embeddings: " + cfg.isEmbeddingsEnabled(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.format("Hooks: PlaceholderAPI=%s Vault=%s LuckPerms=%s EssentialsX=%s",
                hooks.isPlaceholderApiHooked(), hooks.isVaultHooked(), hooks.isLuckPermsHooked(), hooks.isEssentialsHooked()),
                NamedTextColor.GRAY));
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("verity.debug")) {
            deny(sender);
            return true;
        }
        boolean newValue = !plugin.getConfigManager().isDebugEnabled();
        plugin.getConfigManager().setDebugEnabled(newValue);
        sender.sendMessage(Component.text("Debug mode " + (newValue ? "enabled" : "disabled") + ".", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleToggle(CommandSender sender, String arg) {
        if (!sender.hasPermission("verity.toggle")) {
            deny(sender);
            return true;
        }
        boolean newValue = switch (arg.toLowerCase()) {
            case "on" -> true;
            case "off" -> false;
            default -> !plugin.getConfigManager().isAiEnabled();
        };
        plugin.getConfigManager().setAiEnabled(newValue);
        sender.sendMessage(Component.text("Verity is now " + (newValue ? "enabled" : "disabled") + ".",
                newValue ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleChatMode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use conversation mode.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("verity.use")) {
            deny(sender);
            return true;
        }
        var cm = plugin.getConversationManager();
        if (cm.isInActiveConversation(player.getUniqueId())) {
            cm.endConversation(player.getUniqueId());
            player.sendMessage(Component.text("Conversation mode ended.", NamedTextColor.YELLOW));
        } else {
            cm.refreshConversationWindow(player.getUniqueId());
            player.sendMessage(Component.text("Conversation mode started — talk to Verity without the trigger. Say \"bye\" to stop.",
                    NamedTextColor.AQUA));
        }
        return true;
    }

    private boolean handlePersonality(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.personality")) {
            deny(sender);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Available: " + String.join(", ", plugin.getConfigManager().getPersonalityNames()),
                    NamedTextColor.GRAY));
            return true;
        }
        boolean ok = plugin.getConfigManager().setActivePersonality(args[1]);
        if (ok) {
            sender.sendMessage(Component.text("Verity's personality set to: " + args[1], NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Unknown personality preset: " + args[1], NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleOwner(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.owner")) {
            deny(sender);
            return true;
        }
        var cfg = plugin.getConfigManager();
        if (args.length < 2) {
            String current = cfg.getOwnerName();
            sender.sendMessage(Component.text(
                    current.isBlank() ? "No server owner is set." : "Current server owner: " + current,
                    NamedTextColor.GRAY));
            return true;
        }
        String name = args[1];
        cfg.setOwnerName(name);
        sender.sendMessage(Component.text("Server owner set to: " + name, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleMap(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can view the map.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("verity.map")) {
            deny(sender);
            return true;
        }
        plugin.getWorldQueryService().buildMiniMap(player).thenAccept(map -> {
            player.sendMessage(Component.text("--- Nearby map (you are P) ---", NamedTextColor.AQUA));
            player.sendMessage(map);
            String mapUrl = plugin.getConfigManager().getMapWebUrl();
            if (mapUrl != null && !mapUrl.isBlank()) {
                player.sendMessage(Component.text("Full web map: " + mapUrl, NamedTextColor.BLUE));
            }
        });
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("verity.stats")) {
            deny(sender);
            return true;
        }
        var stats = plugin.getStatsService();
        sender.sendMessage(Component.text("--- VerityAI stats (since last restart) ---", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Uptime: " + stats.getUptimeMinutes() + " min", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Requests: " + stats.getTotalRequests()
                + " (ok=" + stats.getSuccessfulRequests() + ", failed=" + stats.getFailedRequests() + ")", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.format("Avg response time: %.0fms", stats.getAverageResponseMs()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Tokens used: " + stats.getTotalTokensUsed(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("AI-run commands: " + stats.getCommandsExecuted(), NamedTextColor.GRAY));

        var topPlayers = stats.topPlayers(5);
        if (!topPlayers.isEmpty()) {
            StringBuilder sb = new StringBuilder("Top askers: ");
            for (var entry : topPlayers) {
                sb.append(entry.getKey()).append(" (").append(entry.getValue()).append("), ");
            }
            sender.sendMessage(Component.text(sb.substring(0, sb.length() - 2), NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean handleModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.model")) {
            deny(sender);
            return true;
        }
        var cfg = plugin.getConfigManager();
        if (args.length < 2) {
            sender.sendMessage(Component.text("Current model: " + cfg.getModel(), NamedTextColor.GRAY));
            if (!cfg.getFallbackModels().isEmpty()) {
                sender.sendMessage(Component.text("Fallback models: " + String.join(", ", cfg.getFallbackModels()), NamedTextColor.GRAY));
            }
            return true;
        }
        cfg.setModel(args[1]);
        sender.sendMessage(Component.text("Primary AI model set to: " + args[1], NamedTextColor.GREEN));
        return true;
    }

    private boolean handleTask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can manage their own reminders.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("verity.task")) {
            deny(sender);
            return true;
        }
        var tasks = plugin.getTaskService();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";

        switch (sub) {
            case "add" -> {
                if (args.length < 4) {
                    player.sendMessage(Component.text("Usage: /verity task add <HH:mm> <message>", NamedTextColor.YELLOW));
                    return true;
                }
                String[] time = args[2].split(":");
                int hour, minute;
                try {
                    hour = Integer.parseInt(time[0]);
                    minute = Integer.parseInt(time[1]);
                    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new NumberFormatException();
                } catch (Exception e) {
                    player.sendMessage(Component.text("Invalid time — use 24-hour HH:mm, e.g. 08:00", NamedTextColor.RED));
                    return true;
                }
                String message = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                String id = tasks.add(player.getUniqueId(), hour, minute, message);
                if (id == null) {
                    player.sendMessage(Component.text("You've hit your reminder limit — remove one first with /verity task remove <id>.", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Reminder set for " + args[2] + " daily (id: " + id + ").", NamedTextColor.GREEN));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /verity task remove <id>", NamedTextColor.YELLOW));
                    return true;
                }
                boolean removed = tasks.remove(player.getUniqueId(), args[2]);
                player.sendMessage(removed
                        ? Component.text("Reminder removed.", NamedTextColor.GREEN)
                        : Component.text("No reminder with that id.", NamedTextColor.RED));
            }
            default -> {
                var list = tasks.list(player.getUniqueId());
                if (list.isEmpty()) {
                    player.sendMessage(Component.text("You have no reminders. Add one: /verity task add <HH:mm> <message>", NamedTextColor.GRAY));
                } else {
                    player.sendMessage(Component.text("--- Your reminders ---", NamedTextColor.AQUA));
                    for (var r : list) {
                        player.sendMessage(Component.text(String.format("[%s] %02d:%02d — %s", r.id(), r.hour(), r.minute(), r.message()), NamedTextColor.GRAY));
                    }
                }
            }
        }
        return true;
    }

    private boolean handleQuest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can request a quest.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("verity.quest")) {
            deny(sender);
            return true;
        }
        player.sendMessage(Component.text("Thinking of a quest for you...", NamedTextColor.GRAY));
        plugin.getQuestService().generate(player);
        return true;
    }

    private boolean handleTutorial(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can request a tutorial.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("verity.use")) {
            deny(sender);
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /verity tutorial <topic>", NamedTextColor.YELLOW));
            return true;
        }
        String topic = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        plugin.getAIHandler().ask(player, "Please give me a clear, step-by-step tutorial on: " + topic);
        return true;
    }

    private boolean handleFeedback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.feedback")) {
            deny(sender);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /verity feedback <good|bad> [correction if bad]", NamedTextColor.YELLOW));
            return true;
        }
        String kind = args[1].toLowerCase(Locale.ROOT);
        if (kind.equals("good")) {
            sender.sendMessage(Component.text("Thanks for the feedback!", NamedTextColor.GREEN));
            return true;
        }
        if (kind.equals("bad")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Add a correction so Verity can avoid repeating the mistake: "
                        + "/verity feedback bad <correction>", NamedTextColor.YELLOW));
                return true;
            }
            String correction = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            plugin.getLessonsService().addLesson(correction);
            sender.sendMessage(Component.text("Noted — Verity will keep that in mind going forward.", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /verity feedback <good|bad> [correction if bad]", NamedTextColor.YELLOW));
        return true;
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "Usage: /verity <reload|clear [player]|info|debug|toggle|chat|personality [name]|owner [player]|"
                        + "map|stats|model [name]|task <add|remove|list>|quest|tutorial <topic>|feedback <good|bad> [correction]>",
                NamedTextColor.YELLOW));
    }
}
