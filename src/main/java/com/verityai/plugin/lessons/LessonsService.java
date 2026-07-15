package com.verityai.plugin.lessons;

import com.verityai.plugin.VerityAI;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * A small, server-wide list of "lessons learned" — corrections staff add via
 * /verity feedback bad <correction> after Verity gets something wrong — kept
 * separate from ai.custom-knowledge (config-only) so it can be curated live
 * in-game without editing config.yml. This is the practical, bounded version
 * of "the AI learns from its mistakes": there's no real learning/training
 * happening, just a growing note staff control that's always shown to the
 * model, capped and persisted so it survives restarts.
 */
public class LessonsService {

    private final VerityAI plugin;
    private final File file;
    private List<String> lessons;

    public LessonsService(VerityAI plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lessons.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            lessons = new ArrayList<>();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        lessons = new ArrayList<>(yaml.getStringList("lessons"));
    }

    public List<String> getLessons() {
        return List.copyOf(lessons);
    }

    public void addLesson(String text) {
        if (text == null || text.isBlank()) return;
        lessons.add(text.trim());
        int cap = Math.max(1, plugin.getConfigManager().getMaxLessons());
        while (lessons.size() > cap) {
            lessons.remove(0);
        }
        save();
    }

    public void clear() {
        lessons.clear();
        save();
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("lessons", lessons);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "VerityAI: failed to save lessons.yml", e);
        }
    }
}
