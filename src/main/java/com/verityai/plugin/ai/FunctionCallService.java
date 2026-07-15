package com.verityai.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verityai.plugin.VerityAI;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Defines the small set of "tools" (functions) Verity is allowed to call when
 * ai.function-calling.enabled is true, and executes them. This is the proper,
 * structured alternative to the ad-hoc [[CMD: ...]] / [REMEMBER: ...] text
 * tags — models that support OpenAI-style tool calling can call these
 * directly and reliably instead of us regex-parsing free text.
 *
 * Every function still goes through the exact same safety checks as the
 * text-tag versions (command whitelist + player-permission dispatch for
 * run_command, long-term-memory dedup/cap for remember_fact) — function
 * calling only changes HOW the model asks, not what it's allowed to do.
 */
public class FunctionCallService {

    private final VerityAI plugin;

    public FunctionCallService(VerityAI plugin) {
        this.plugin = plugin;
    }

    /** Builds the "tools" array to send to the model, based on what's currently available to this player. */
    public JsonArray buildToolSchemas(Player player) {
        JsonArray tools = new JsonArray();
        tools.add(tool("get_player_status", "Get the asking player's live status: health, food, gamemode, "
                + "world, and coordinates.", emptyParams()));
        tools.add(tool("get_nearby_terrain", "Get a real summary of the biomes/terrain around the player, "
                + "useful when they ask about the map or their surroundings.", emptyParams()));
        tools.add(tool("get_server_time", "Get the current real-world time and the in-game Minecraft time "
                + "(day/night) for the player's world.", emptyParams()));
        tools.add(tool("get_nearest_stronghold", "Find the coordinates of the nearest stronghold structure.", emptyParams()));
        tools.add(tool("get_environment", "Get the current weather, biome, and dimension (Overworld/Nether/End) "
                + "at the player's location.", emptyParams()));
        tools.add(tool("get_craftable_items", "Check the player's inventory and list what they can craft "
                + "right now with the materials they're carrying.", emptyParams()));

        if (plugin.getConfigManager().isLongTermEnabled() && plugin.getConfigManager().isAutoRememberEnabled()) {
            JsonObject params = new JsonObject();
            JsonObject props = new JsonObject();
            JsonObject factProp = new JsonObject();
            factProp.addProperty("type", "string");
            factProp.addProperty("description", "A short, standalone fact worth remembering about this player. "
                    + "Only call this for things the player actually told you.");
            props.add("fact", factProp);

            JsonObject categoryProp = new JsonObject();
            categoryProp.addProperty("type", "string");
            JsonArray categoryEnum = new JsonArray();
            for (String c : new String[] {"interest", "friend", "project", "playstyle", "goal", "general"}) {
                categoryEnum.add(c);
            }
            categoryProp.add("enum", categoryEnum);
            categoryProp.addProperty("description", "What kind of fact this is.");
            props.add("category", categoryProp);

            params.addProperty("type", "object");
            params.add("properties", props);
            JsonArray required = new JsonArray();
            required.add("fact");
            required.add("category");
            params.add("required", required);
            tools.add(tool("remember_fact", "Permanently remember a categorized fact about this player "
                    + "(interest, friend, project, playstyle, or goal) for future conversations.", params));
        }

        if (plugin.getConfigManager().isCommandsEnabled()) {
            var cfg = plugin.getConfigManager();
            boolean isOwner = cfg.isOwner(player.getName());
            var whitelist = cfg.getCommandWhitelist();
            var ownerWhitelist = isOwner ? cfg.getOwnerCommandWhitelist() : java.util.List.<String>of();
            if ((whitelist != null && !whitelist.isEmpty()) || !ownerWhitelist.isEmpty()) {
                JsonObject params = new JsonObject();
                JsonObject props = new JsonObject();
                JsonObject cmdProp = new JsonObject();
                cmdProp.addProperty("type", "string");
                StringBuilder desc = new StringBuilder("The exact command to run, without the leading slash. Allowed: ");
                if (whitelist != null) desc.append(String.join(", ", whitelist));
                if (!ownerWhitelist.isEmpty()) desc.append(" | owner-only: ").append(String.join(", ", ownerWhitelist));
                cmdProp.addProperty("description", desc.toString());
                props.add("command", cmdProp);
                params.addProperty("type", "object");
                params.add("properties", props);
                JsonArray required = new JsonArray();
                required.add("command");
                params.add("required", required);
                tools.add(tool("run_command", "Run an in-game command for the player. Only use this if the "
                        + "player clearly and explicitly asked you to perform that action right now, and only with "
                        + "a command from the allowed list.", params));
            }
        }

        return tools;
    }

    /** Executes a named function for a given player and returns a short text/JSON result for the model. */
    public String execute(Player player, String functionName, String argumentsJson) throws Exception {
        JsonObject args;
        try {
            args = JsonParser.parseString(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson).getAsJsonObject();
        } catch (Exception e) {
            args = new JsonObject();
        }

        return switch (functionName) {
            case "get_player_status" -> getPlayerStatus(player);
            case "get_nearby_terrain" -> getNearbyTerrain(player);
            case "get_server_time" -> getServerTime(player);
            case "get_nearest_stronghold" -> getNearestStronghold(player);
            case "get_environment" -> getEnvironment(player);
            case "get_craftable_items" -> getCraftableItems(player);
            case "remember_fact" -> rememberFact(player, args);
            case "run_command" -> runCommand(player, args);
            default -> "Unknown function: " + functionName;
        };
    }

    /**
     * Scans the player's inventory and checks it against every registered shaped/shapeless
     * recipe, returning what they could craft right now. An approximation (doesn't account
     * for recipe-book unlock state or every possible RecipeChoice edge case), but covers the
     * common vanilla case well. Capped to keep the check itself and the response both short.
     */
    private String getCraftableItems(Player player) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                java.util.Map<org.bukkit.Material, Integer> counts = new java.util.HashMap<>();
                for (var stack : player.getInventory().getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
                    }
                }

                java.util.List<String> craftable = new java.util.ArrayList<>();
                java.util.Iterator<org.bukkit.inventory.Recipe> it = org.bukkit.Bukkit.recipeIterator();
                int checked = 0;
                while (it.hasNext() && craftable.size() < 15 && checked < 3000) {
                    checked++;
                    org.bukkit.inventory.Recipe recipe = it.next();
                    if (canCraft(recipe, counts)) {
                        String name = recipe.getResult().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                        if (!craftable.contains(name)) {
                            craftable.add(name);
                        }
                    }
                }
                future.complete(craftable.isEmpty() ? "Nothing craftable with current inventory."
                        : "Craftable now: " + String.join(", ", craftable));
            } catch (Throwable t) {
                future.complete("crafting check failed: " + t.getMessage());
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "crafting check timed out: " + e.getMessage();
        }
    }

    private boolean canCraft(org.bukkit.inventory.Recipe recipe, java.util.Map<org.bukkit.Material, Integer> counts) {
        java.util.Map<org.bukkit.Material, Integer> remaining = new java.util.HashMap<>(counts);
        java.util.List<Object[]> requirements = new java.util.ArrayList<>(); // [RecipeChoice, Integer qty]

        if (recipe instanceof org.bukkit.inventory.ShapedRecipe shaped) {
            java.util.Map<Character, Integer> charCounts = new java.util.HashMap<>();
            for (String row : shaped.getShape()) {
                for (char c : row.toCharArray()) {
                    if (c != ' ') charCounts.merge(c, 1, Integer::sum);
                }
            }
            for (var entry : shaped.getChoiceMap().entrySet()) {
                if (entry.getValue() == null) continue;
                requirements.add(new Object[] { entry.getValue(), charCounts.getOrDefault(entry.getKey(), 1) });
            }
        } else if (recipe instanceof org.bukkit.inventory.ShapelessRecipe shapeless) {
            for (var choice : shapeless.getChoiceList()) {
                if (choice == null) continue;
                requirements.add(new Object[] { choice, 1 });
            }
        } else {
            return false; // furnace/smithing/etc. recipes aren't a quick single-inventory check
        }

        if (requirements.isEmpty()) return false;

        for (Object[] req : requirements) {
            org.bukkit.inventory.RecipeChoice choice = (org.bukkit.inventory.RecipeChoice) req[0];
            int qty = (Integer) req[1];
            org.bukkit.Material picked = pickAvailableMaterial(choice, remaining, qty);
            if (picked == null) return false;
            remaining.merge(picked, -qty, Integer::sum);
        }
        return true;
    }

    /** Finds a material within this ingredient choice that the player actually has enough of. */
    private org.bukkit.Material pickAvailableMaterial(org.bukkit.inventory.RecipeChoice choice,
                                                        java.util.Map<org.bukkit.Material, Integer> remaining, int qty) {
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice materialChoice) {
            for (org.bukkit.Material m : materialChoice.getChoices()) {
                if (remaining.getOrDefault(m, 0) >= qty) return m;
            }
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ExactChoice exactChoice) {
            for (var stack : exactChoice.getChoices()) {
                if (remaining.getOrDefault(stack.getType(), 0) >= qty) return stack.getType();
            }
        }
        return null;
    }

    private String getNearestStronghold(Player player) {
        try {
            return plugin.getWorldQueryService().findNearestStronghold(player).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "stronghold lookup failed: " + e.getMessage();
        }
    }

    private String getEnvironment(Player player) {
        try {
            return plugin.getWorldQueryService().getCurrentEnvironmentSummary(player).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "environment lookup failed: " + e.getMessage();
        }
    }

    private String getPlayerStatus(Player player) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                var loc = player.getLocation();
                var maxHealthAttr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                future.complete(String.format(
                        "health=%.1f/%.1f, food=%d/20, gamemode=%s, world=%s, x=%d, y=%d, z=%d",
                        player.getHealth(), maxHealth, player.getFoodLevel(), player.getGameMode(),
                        player.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            } catch (Throwable t) {
                future.complete("status lookup failed: " + t.getMessage());
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "status lookup timed out: " + e.getMessage();
        }
    }

    private String getNearbyTerrain(Player player) {
        try {
            return plugin.getWorldQueryService().describeSurroundingTerrain(player).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "terrain lookup failed: " + e.getMessage();
        }
    }

    private String getServerTime(Player player) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                long ticks = player.getWorld().getTime();
                String phase = (ticks < 12000) ? "day" : (ticks < 13800) ? "sunset" : (ticks < 22200) ? "night" : "sunrise";
                future.complete("real-world-time=" + java.time.LocalDateTime.now() + ", in-game-ticks=" + ticks + ", phase=" + phase);
            } catch (Throwable t) {
                future.complete("time lookup failed: " + t.getMessage());
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "time lookup timed out: " + e.getMessage();
        }
    }

    private String rememberFact(Player player, JsonObject args) {
        if (!args.has("fact") || args.get("fact").getAsString().isBlank()) {
            return "No fact provided.";
        }
        String fact = args.get("fact").getAsString().trim();
        String category = args.has("category") ? args.get("category").getAsString() : "general";
        plugin.getLongTermMemoryStore().addCategorizedFact(player.getUniqueId(), category, fact);
        return "Remembered (" + category + ").";
    }

    private String runCommand(Player player, JsonObject args) {
        if (!args.has("command") || args.get("command").getAsString().isBlank()) {
            return "No command provided.";
        }
        String command = args.get("command").getAsString().trim();
        boolean ok = plugin.getAiCommandExecutor().executeIfAllowed(player, command);
        return ok ? "Command executed: /" + command : "That command isn't allowed for this player.";
    }

    private JsonObject tool(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        wrapper.add("function", function);
        return wrapper;
    }

    private JsonObject emptyParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", new JsonObject());
        return params;
    }
}
