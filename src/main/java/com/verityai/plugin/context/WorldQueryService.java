package com.verityai.plugin.context;

import com.verityai.plugin.VerityAI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BiomeSearchResult;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only queries about the live game world. Anything that touches world
 * generation (structures/biomes) MUST run on the main thread, so these
 * methods hop back to it via the scheduler and return a CompletableFuture
 * that resolves off-thread once the sync lookup finishes.
 *
 * Note: structure/biome lookup radii are capped to keep them cheap — a wide
 * search can briefly stall the main thread since chunk generation may be
 * involved. Adjust radii below if you need a wider search on a beefier server.
 */
public class WorldQueryService {

    private static final int SEARCH_RADIUS_CHUNKS = 100;

    private final VerityAI plugin;

    public WorldQueryService(VerityAI plugin) {
        this.plugin = plugin;
    }

    public double getTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return Math.min(20.0, tps[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    public int getOnlineCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    public String getServerTimeOfDay(World world) {
        long time = world.getTime() % 24000;
        String phase;
        if (time < 6000) phase = "morning";
        else if (time < 12000) phase = "day";
        else if (time < 13000) phase = "sunset";
        else if (time < 22000) phase = "night";
        else phase = "sunrise";
        long hours = ((time / 1000) + 6) % 24;
        long minutes = (time % 1000) * 60 / 1000;
        return String.format("%02d:%02d (%s)", hours, minutes, phase);
    }

    public Optional<Player> findNearestPlayer(Player from) {
        return from.getWorld().getPlayers().stream()
                .filter(p -> !p.getUniqueId().equals(from.getUniqueId()))
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(from.getLocation())));
    }

    public double distance(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) {
            return -1;
        }
        return a.distance(b);
    }

    /** Locates the nearest plains village structure, run safely on the main thread. */
    public CompletableFuture<String> findNearestVillage(Player player) {
        return findNearestStructure(player, Structure.VILLAGE_PLAINS);
    }

    public CompletableFuture<String> findNearestStructure(Player player, Structure structure) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (structure == null) {
            future.complete("unknown structure type");
            return future;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                var result = player.getWorld().locateNearestStructure(
                        player.getLocation(), structure, SEARCH_RADIUS_CHUNKS, false);
                if (result == null) {
                    future.complete("none found within search radius");
                } else {
                    Location loc = result.getLocation();
                    future.complete(String.format("x=%d, y=%d, z=%d (%.0f blocks away)",
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                            loc.distance(player.getLocation())));
                }
            } catch (Throwable t) {
                future.complete("lookup failed: " + t.getMessage());
            }
        });
        return future;
    }

    /** Locates the nearest stronghold structure, run safely on the main thread. */
    public CompletableFuture<String> findNearestStronghold(Player player) {
        return findNearestStructure(player, Structure.STRONGHOLD);
    }

    /**
     * Combined weather/biome/dimension snapshot for the player's current location —
     * cheap reads, but still hopped to the main thread for consistency/safety since
     * this is normally called from the async AI-request thread.
     */
    public CompletableFuture<String> getCurrentEnvironmentSummary(Player player) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                World world = player.getWorld();
                String weather = world.isThundering() ? "thunderstorm" : world.hasStorm() ? "raining" : "clear";
                Biome biome = world.getBiome(player.getLocation());
                String dimension = switch (world.getEnvironment()) {
                    case NETHER -> "The Nether";
                    case THE_END -> "The End";
                    default -> "Overworld";
                };
                future.complete(String.format("dimension=%s, weather=%s, biome=%s, world=%s",
                        dimension, weather, biomeName(biome), world.getName()));
            } catch (Throwable t) {
                future.complete("environment lookup failed: " + t.getMessage());
            }
        });
        return future;
    }

    private static final int MAP_GRID_RADIUS = 4; // 9x9 grid centered on the player

    /**
     * Samples a small grid of real biomes/heights around the player and returns a short
     * textual summary, so the AI can talk about "the map" using real data instead of guessing.
     */
    public CompletableFuture<String> describeSurroundingTerrain(Player player) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Location origin = player.getLocation();
                java.util.Map<String, Integer> biomeCounts = new java.util.LinkedHashMap<>();
                for (int dx = -MAP_GRID_RADIUS; dx <= MAP_GRID_RADIUS; dx++) {
                    for (int dz = -MAP_GRID_RADIUS; dz <= MAP_GRID_RADIUS; dz++) {
                        int x = origin.getBlockX() + dx * 16;
                        int z = origin.getBlockZ() + dz * 16;
                        Biome biome = player.getWorld().getBiome(x, origin.getBlockY(), z);
                        biomeCounts.merge(biomeName(biome), 1, Integer::sum);
                    }
                }
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (var entry : biomeCounts.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(entry.getValue()).append("x ").append(entry.getKey());
                    first = false;
                }
                future.complete(sb.toString());
            } catch (Throwable t) {
                future.complete("terrain lookup failed: " + t.getMessage());
            }
        });
        return future;
    }

    /**
     * Renders a simple top-down ASCII mini-map (biome-colored letters, player at the
     * center) for display directly in chat via /verity map — this is independent of the
     * AI and always reflects the real world.
     */
    public CompletableFuture<net.kyori.adventure.text.Component> buildMiniMap(Player player) {
        CompletableFuture<net.kyori.adventure.text.Component> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Location origin = player.getLocation();
                net.kyori.adventure.text.Component map = net.kyori.adventure.text.Component.empty();
                for (int dz = -MAP_GRID_RADIUS; dz <= MAP_GRID_RADIUS; dz++) {
                    net.kyori.adventure.text.Component row = net.kyori.adventure.text.Component.empty();
                    for (int dx = -MAP_GRID_RADIUS; dx <= MAP_GRID_RADIUS; dx++) {
                        if (dx == 0 && dz == 0) {
                            row = row.append(net.kyori.adventure.text.Component.text("P",
                                    net.kyori.adventure.text.format.NamedTextColor.RED));
                            continue;
                        }
                        int x = origin.getBlockX() + dx * 16;
                        int z = origin.getBlockZ() + dz * 16;
                        Biome biome = player.getWorld().getBiome(x, origin.getBlockY(), z);
                        row = row.append(net.kyori.adventure.text.Component.text(biomeSymbol(biome), biomeColor(biome)));
                    }
                    map = map.append(row).append(net.kyori.adventure.text.Component.newline());
                }
                future.complete(map);
            } catch (Throwable t) {
                future.complete(net.kyori.adventure.text.Component.text("Map lookup failed: " + t.getMessage()));
            }
        });
        return future;
    }

    private String biomeName(Biome biome) {
        String key = biome.getKey().getKey();
        return key.replace('_', ' ');
    }

    private String biomeSymbol(Biome biome) {
        String key = biome.getKey().getKey();
        if (key.contains("ocean") || key.contains("river")) return "~";
        if (key.contains("desert")) return "d";
        if (key.contains("forest") || key.contains("taiga") || key.contains("jungle")) return "f";
        if (key.contains("mountain") || key.contains("hill") || key.contains("peak")) return "^";
        if (key.contains("swamp")) return "s";
        if (key.contains("snow") || key.contains("ice") || key.contains("frozen")) return "*";
        if (key.contains("plains") || key.contains("savanna") || key.contains("meadow")) return ".";
        return "?";
    }

    private net.kyori.adventure.text.format.NamedTextColor biomeColor(Biome biome) {
        String key = biome.getKey().getKey();
        if (key.contains("ocean") || key.contains("river")) return net.kyori.adventure.text.format.NamedTextColor.BLUE;
        if (key.contains("desert")) return net.kyori.adventure.text.format.NamedTextColor.YELLOW;
        if (key.contains("forest") || key.contains("taiga") || key.contains("jungle")) return net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN;
        if (key.contains("mountain") || key.contains("hill") || key.contains("peak")) return net.kyori.adventure.text.format.NamedTextColor.GRAY;
        if (key.contains("swamp")) return net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE;
        if (key.contains("snow") || key.contains("ice") || key.contains("frozen")) return net.kyori.adventure.text.format.NamedTextColor.WHITE;
        return net.kyori.adventure.text.format.NamedTextColor.GREEN;
    }

    public CompletableFuture<String> findNearestBiome(Player player, Biome biome) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                BiomeSearchResult result = player.getWorld()
                        .locateNearestBiome(player.getLocation(), SEARCH_RADIUS_CHUNKS, biome);
                if (result == null) {
                    future.complete("none found within search radius");
                } else {
                    Location loc = result.getLocation();
                    future.complete(String.format("x=%d, y=%d, z=%d (%.0f blocks away)",
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                            loc.distance(player.getLocation())));
                }
            } catch (Throwable t) {
                future.complete("lookup failed: " + t.getMessage());
            }
        });
        return future;
    }
}
