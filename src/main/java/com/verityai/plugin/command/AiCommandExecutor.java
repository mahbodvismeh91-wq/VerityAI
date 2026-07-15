package com.verityai.plugin.command;

import com.verityai.plugin.VerityAI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets Verity execute real in-game commands when a player explicitly asks it to,
 * WITHOUT letting the AI ever gain more power than the player already has.
 *
 * Safety model:
 *  - Disabled by default (commands.enabled: false in config.yml).
 *  - The AI can only request commands whose base word is in commands.whitelist
 *    (or commands.owner-only-console-whitelist, only for the configured owner) —
 *    both lists are shown to the model in the system prompt, so it never invents one.
 *  - For regular players, the command is dispatched AS THAT PLAYER via
 *    Bukkit.dispatchCommand(player, ...), so Bukkit's existing permission system
 *    still applies in full: Verity can never let a player do anything they
 *    couldn't already do themselves by typing the command directly.
 *  - Only the owner-only-console-whitelist (empty by default) is ever run with
 *    console privileges, and only for the single configured owner.
 */
public class AiCommandExecutor {

    // Matches [[CMD: <command>]] (case-insensitive), command text without the leading slash.
    private static final Pattern CMD_TAG = Pattern.compile("\\[\\[CMD:\\s*(.+?)\\s*]]", Pattern.CASE_INSENSITIVE);

    private final VerityAI plugin;

    public AiCommandExecutor(VerityAI plugin) {
        this.plugin = plugin;
    }

    public record ExtractResult(String cleanedText, List<String> executedCommands, List<String> rejectedCommands) {}

    /**
     * Strips every [[CMD: ...]] tag out of the AI's answer and, if commands are
     * enabled, executes the whitelisted ones for this player. Must be called
     * from any thread; actual dispatch always hops to the main thread.
     */
    public ExtractResult process(Player player, String answer) {
        var cfg = plugin.getConfigManager();
        List<String> executed = new java.util.ArrayList<>();
        List<String> rejected = new java.util.ArrayList<>();

        if (answer == null || answer.isBlank()) {
            return new ExtractResult(answer, executed, rejected);
        }

        Matcher matcher = CMD_TAG.matcher(answer);
        String cleaned = matcher.replaceAll("").stripTrailing();

        if (!cfg.isCommandsEnabled()) {
            return new ExtractResult(cleaned, executed, rejected);
        }

        matcher.reset();
        while (matcher.find()) {
            String command = matcher.group(1).trim();
            if (command.isEmpty()) continue;

            if (tryExecute(player, command)) {
                executed.add(command);
            } else {
                rejected.add(command);
            }
        }

        return new ExtractResult(cleaned, executed, rejected);
    }

    private boolean tryExecute(Player player, String command) {
        return executeIfAllowed(player, command);
    }

    /**
     * Checks the command against the whitelist(s) and dispatches it if allowed.
     * Public so both the [[CMD: ...]] text-tag path and real function-calling
     * (FunctionCallService) share the exact same safety checks.
     */
    public boolean executeIfAllowed(Player player, String command) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isCommandsEnabled()) {
            return false;
        }
        String base = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

        boolean isOwner = cfg.isOwner(player.getName());
        boolean inOwnerConsoleList = isOwner && containsBase(cfg.getOwnerCommandWhitelist(), base);
        boolean inPlayerList = containsBase(cfg.getCommandWhitelist(), base);

        if (inOwnerConsoleList) {
            // Elevated: runs as console, but ONLY for the single configured owner and
            // ONLY for commands the admin explicitly opted into this list.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                Bukkit.getPluginManager().callEvent(
                        new com.verityai.api.events.VerityCommandExecutedEvent(player, command, true));
            });
            plugin.getDebugLogger().debug("VerityAI: executed owner command (console) for " + player.getName() + ": /" + command);
            return true;
        }

        if (inPlayerList) {
            // Runs AS the player — Bukkit's own permission checks still apply,
            // so this can never grant more than the player already has.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(player, command);
                Bukkit.getPluginManager().callEvent(
                        new com.verityai.api.events.VerityCommandExecutedEvent(player, command, false));
            });
            plugin.getDebugLogger().debug("VerityAI: executed player command for " + player.getName() + ": /" + command);
            return true;
        }

        plugin.getDebugLogger().debug("VerityAI: rejected non-whitelisted AI command from "
                + player.getName() + ": /" + command);
        return false;
    }

    private boolean containsBase(List<String> whitelist, String base) {
        if (whitelist == null) return false;
        for (String entry : whitelist) {
            if (entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(base)) {
                return true;
            }
        }
        return false;
    }
}
