package com.verityai.plugin.tasks;

import com.verityai.plugin.VerityAI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Simple daily reminders per player ("remind me at 8am to check the farm").
 * Persisted under plugins/VerityAI/tasks/<uuid>.yml, checked once a minute
 * on the main thread. A reminder only actually reaches the player if they're
 * online at that exact minute — there's no offline mail/queue here, just a
 * lightweight in-game nudge, which keeps this simple and dependency-free.
 */
public class TaskService {

    /** One reminder: fires once per day at hour:minute, tracked so it won't repeat within the same day. */
    public record Reminder(String id, int hour, int minute, String message, String lastTriggeredDate) {}

    private final VerityAI plugin;
    private final File folder;
    private final Map<UUID, List<Reminder>> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    public TaskService(VerityAI plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "tasks");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("VerityAI: could not create tasks folder.");
        }
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private int maxPerPlayer() {
        return Math.max(1, plugin.getConfigManager().getMaxTasksPerPlayer());
    }

    public List<Reminder> list(UUID uuid) {
        return List.copyOf(loadIfAbsent(uuid));
    }

    private List<Reminder> loadIfAbsent(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            File file = fileFor(id);
            List<Reminder> result = new ArrayList<>();
            if (!file.exists()) return result;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (Map<?, ?> row : yaml.getMapList("reminders")) {
                try {
                    result.add(new Reminder(
                            String.valueOf(row.get("id")),
                            Integer.parseInt(row.get("hour").toString()),
                            Integer.parseInt(row.get("minute").toString()),
                            String.valueOf(row.get("message")),
                            row.get("lastTriggeredDate") != null ? row.get("lastTriggeredDate").toString() : ""));
                } catch (Exception ignored) {
                    // skip a malformed row rather than failing the whole load
                }
            }
            return result;
        });
    }

    /** Returns the new reminder's id, or null if the player is already at their cap. */
    public String add(UUID uuid, int hour, int minute, String message) {
        List<Reminder> reminders = loadIfAbsent(uuid);
        if (reminders.size() >= maxPerPlayer()) {
            return null;
        }
        String id = "r" + idCounter.getAndIncrement();
        reminders.add(new Reminder(id, hour, minute, message, ""));
        dirty.add(uuid);
        return id;
    }

    public boolean remove(UUID uuid, String id) {
        List<Reminder> reminders = loadIfAbsent(uuid);
        boolean removed = reminders.removeIf(r -> r.id().equals(id));
        if (removed) dirty.add(uuid);
        return removed;
    }

    /** Checked once a minute (main thread): fires any due reminder for currently online players. */
    public void checkDue() {
        String today = LocalDate.now().toString();
        LocalTime now = LocalTime.now();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            List<Reminder> reminders = loadIfAbsent(uuid); // cheap no-op once cached
            if (reminders.isEmpty()) continue;

            for (int i = 0; i < reminders.size(); i++) {
                Reminder r = reminders.get(i);
                if (r.hour() == now.getHour() && r.minute() == now.getMinute() && !today.equals(r.lastTriggeredDate())) {
                    player.sendMessage(Component.text("⏰ Reminder: " + r.message(), NamedTextColor.YELLOW));
                    reminders.set(i, new Reminder(r.id(), r.hour(), r.minute(), r.message(), today));
                    dirty.add(uuid);
                }
            }
        }
    }

    public void flushDirty() {
        if (dirty.isEmpty()) return;
        for (UUID uuid : Set.copyOf(dirty)) {
            List<Reminder> reminders = cache.get(uuid);
            if (reminders != null) {
                save(uuid, reminders);
            }
            dirty.remove(uuid);
        }
    }

    private void save(UUID uuid, List<Reminder> reminders) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Reminder r : reminders) {
            rows.add(Map.of("id", r.id(), "hour", r.hour(), "minute", r.minute(),
                    "message", r.message(), "lastTriggeredDate", r.lastTriggeredDate()));
        }
        yaml.set("reminders", rows);
        try {
            yaml.save(fileFor(uuid));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "VerityAI: failed to save reminders for " + uuid, e);
        }
    }
}
