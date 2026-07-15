package com.verityai.plugin.integration;

import com.verityai.plugin.VerityAI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Optional;

/**
 * Detects and binds optional third-party plugins at startup. Every lookup is
 * wrapped in try/catch so a missing dependency never crashes VerityAI —
 * features tied to that plugin are simply skipped.
 */
public class HookManager {

    private final VerityAI plugin;

    private boolean placeholderApiHooked;
    private boolean vaultHooked;
    private boolean luckPermsHooked;
    private boolean essentialsHooked;

    private net.milkbowl.vault.economy.Economy economy;
    private net.luckperms.api.LuckPerms luckPerms;
    private com.earth2me.essentials.Essentials essentials;

    public HookManager(VerityAI plugin) {
        this.plugin = plugin;
    }

    public void hookAll() {
        hookPlaceholderApi();
        hookVault();
        hookLuckPerms();
        hookEssentials();
    }

    private void hookPlaceholderApi() {
        if (!plugin.getConfigManager().isPlaceholderApiIntegrationEnabled()) return;
        try {
            placeholderApiHooked = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
            if (placeholderApiHooked) plugin.getLogger().info("VerityAI: hooked into PlaceholderAPI.");
        } catch (Throwable t) {
            placeholderApiHooked = false;
        }
    }

    private void hookVault() {
        if (!plugin.getConfigManager().isVaultIntegrationEnabled()) return;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
                vaultHooked = true;
                plugin.getLogger().info("VerityAI: hooked into Vault economy.");
            }
        } catch (Throwable t) {
            vaultHooked = false;
        }
    }

    private void hookLuckPerms() {
        if (!plugin.getConfigManager().isLuckPermsIntegrationEnabled()) return;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return;
            luckPerms = net.luckperms.api.LuckPermsProvider.get();
            luckPermsHooked = true;
            plugin.getLogger().info("VerityAI: hooked into LuckPerms.");
        } catch (Throwable t) {
            luckPermsHooked = false;
        }
    }

    private void hookEssentials() {
        if (!plugin.getConfigManager().isEssentialsXIntegrationEnabled()) return;
        try {
            org.bukkit.plugin.Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
            if (ess instanceof com.earth2me.essentials.Essentials essentialsPlugin) {
                essentials = essentialsPlugin;
                essentialsHooked = true;
                plugin.getLogger().info("VerityAI: hooked into EssentialsX.");
            }
        } catch (Throwable t) {
            essentialsHooked = false;
        }
    }

    // ---- PlaceholderAPI ----
    public String parsePlaceholders(Player player, String text) {
        if (!placeholderApiHooked || text == null) return text;
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }

    // ---- Vault ----
    public Optional<Double> getBalance(Player player) {
        if (!vaultHooked || economy == null) return Optional.empty();
        try {
            return Optional.of(economy.getBalance(player));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    // ---- LuckPerms ----
    public Optional<String> getPrimaryGroup(Player player) {
        if (!luckPermsHooked || luckPerms == null) return Optional.empty();
        try {
            net.luckperms.api.model.user.User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) return Optional.empty();
            return Optional.ofNullable(user.getPrimaryGroup());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    // ---- EssentialsX ----
    public List<String> getHomes(Player player) {
        if (!essentialsHooked || essentials == null) return List.of();
        try {
            com.earth2me.essentials.User user = essentials.getUser(player);
            if (user == null) return List.of();
            return user.getHomes();
        } catch (Throwable t) {
            return List.of();
        }
    }

    public boolean isPlaceholderApiHooked() { return placeholderApiHooked; }
    public boolean isVaultHooked() { return vaultHooked; }
    public boolean isLuckPermsHooked() { return luckPermsHooked; }
    public boolean isEssentialsHooked() { return essentialsHooked; }
}
