package com.verityai.plugin.memory;

import com.verityai.plugin.VerityAI;
import com.verityai.plugin.ai.EmbeddingService;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Per-player long-term memory: a small set of persisted facts/notes,
 * stored under plugins/VerityAI/memory/<uuid>.yml and re-injected into the
 * system prompt on every request when memory.long-term-enabled is true.
 *
 * Performance: everything lives in an in-RAM cache (loaded lazily on first
 * access) so a busy conversation never re-opens the YAML file on disk for
 * every single message. Writes only mark the player's entry "dirty"; an
 * async task (scheduled from VerityAI, memory.autosave-interval-minutes)
 * periodically flushes dirty entries to disk in a batch, and onDisable()
 * always does one final flush so nothing is lost on shutdown. clear() is
 * the one exception — since it's a deliberate, infrequent action, it writes
 * (deletes) immediately rather than waiting for the next autosave.
 *
 * Semantic memory (ai.embeddings.enabled): each fact optionally gets an
 * embedding vector alongside it, kept in the same order as the facts list
 * (and persisted the same way). When enabled, new facts are deduplicated by
 * MEANING (cosine similarity) instead of just text overlap, and
 * getRelevantFacts() can return only the facts most relevant to the current
 * question instead of the entire list — useful once a player has
 * accumulated a lot of memories. Embeddings are entirely optional; every
 * embedding-related step gracefully falls back to the plain-text behavior
 * if disabled or if a call fails.
 */
public class LongTermMemoryStore {

    private final VerityAI plugin;
    private final File folder;

    private final Map<UUID, List<String>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, List<double[]>> embeddingsCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public LongTermMemoryStore(VerityAI plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "memory");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("VerityAI: could not create memory folder.");
        }
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private int maxEntries() {
        return Math.max(1, plugin.getConfigManager().getLongTermMaxEntries());
    }

    private EmbeddingService embeddings() {
        return plugin.getEmbeddingService();
    }

    public List<String> getFacts(UUID uuid) {
        return List.copyOf(loadIfAbsent(uuid));
    }

    /**
     * Returns up to topK facts most relevant to queryText (by embedding similarity)
     * when embeddings are enabled and available; otherwise returns all known facts
     * (capped at topK if positive) so callers get sensible behavior either way.
     */
    public List<String> getRelevantFacts(UUID uuid, String queryText, int topK) {
        List<String> facts = loadIfAbsent(uuid);
        if (facts.isEmpty()) return List.of();

        if (embeddings().isEnabled()) {
            double[] queryVector = embeddings().embedOrNull(queryText);
            List<double[]> vectors = embeddingsCache.get(uuid);
            if (queryVector != null && vectors != null && vectors.size() == facts.size()) {
                Integer[] indices = new Integer[facts.size()];
                double[] scores = new double[facts.size()];
                for (int i = 0; i < facts.size(); i++) {
                    indices[i] = i;
                    scores[i] = EmbeddingService.cosineSimilarity(queryVector, vectors.get(i));
                }
                java.util.Arrays.sort(indices, (x, y) -> Double.compare(scores[y], scores[x]));
                List<String> result = new ArrayList<>();
                for (int i = 0; i < Math.min(topK > 0 ? topK : indices.length, indices.length); i++) {
                    result.add(facts.get(indices[i]));
                }
                return result;
            }
        }

        // Fallback: no embeddings available — return the most recent facts, capped.
        if (topK > 0 && facts.size() > topK) {
            return new ArrayList<>(facts.subList(facts.size() - topK, facts.size()));
        }
        return List.copyOf(facts);
    }

    private List<String> loadIfAbsent(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            File file = fileFor(id);
            if (!file.exists()) {
                embeddingsCache.put(id, new CopyOnWriteArrayList<>());
                return new CopyOnWriteArrayList<>();
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<String> facts = new CopyOnWriteArrayList<>(yaml.getStringList("facts"));

            List<double[]> vectors = new CopyOnWriteArrayList<>();
            List<?> rawVectors = yaml.getList("embeddings");
            if (rawVectors != null) {
                for (Object row : rawVectors) {
                    if (row instanceof List<?> rowList) {
                        double[] vec = new double[rowList.size()];
                        for (int i = 0; i < rowList.size(); i++) {
                            vec[i] = ((Number) rowList.get(i)).doubleValue();
                        }
                        vectors.add(vec);
                    }
                }
            }
            embeddingsCache.put(id, vectors);
            return facts;
        });
    }

    private static final java.util.Set<String> KNOWN_CATEGORIES = java.util.Set.of(
            "interest", "friend", "project", "playstyle", "goal", "general");

    /**
     * Stores a fact tagged with a category (interest/friend/project/playstyle/goal/general)
     * so memories can be grouped meaningfully instead of being one flat list — the category
     * is just encoded as a "[category] " prefix on the stored text, so it works with the
     * existing plain-string storage/embeddings/dedup logic without any format changes.
     */
    public void addCategorizedFact(UUID uuid, String category, String fact) {
        if (fact == null || fact.isBlank()) return;
        String normalizedCategory = (category == null || !KNOWN_CATEGORIES.contains(category.toLowerCase(Locale.ROOT)))
                ? "general" : category.toLowerCase(Locale.ROOT);
        addFact(uuid, "[" + normalizedCategory + "] " + fact.trim());
    }

    /** Splits a stored fact into its category (or "general" if untagged) and its text. */
    public static String[] splitCategory(String storedFact) {
        if (storedFact != null && storedFact.startsWith("[")) {
            int end = storedFact.indexOf(']');
            if (end > 0 && end < storedFact.length() - 1) {
                String category = storedFact.substring(1, end);
                if (KNOWN_CATEGORIES.contains(category)) {
                    return new String[] { category, storedFact.substring(end + 1).trim() };
                }
            }
        }
        return new String[] { "general", storedFact };
    }

    public void addFact(UUID uuid, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        String trimmed = fact.trim();
        List<String> facts = loadIfAbsent(uuid);
        List<double[]> vectors = embeddingsCache.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>());

        double[] newVector = embeddings().isEnabled() ? embeddings().embedOrNull(trimmed) : null;

        if (isDuplicate(facts, vectors, trimmed, newVector)) {
            return;
        }

        facts.add(trimmed);
        vectors.add(newVector); // may be null if embeddings disabled/failed — kept parallel to facts either way

        while (facts.size() > maxEntries()) {
            facts.remove(0);
            if (!vectors.isEmpty()) vectors.remove(0);
        }
        dirty.add(uuid);
    }

    /**
     * Skips near-duplicate facts. Prefers semantic (embedding) similarity when
     * available for this candidate and all existing facts have vectors; falls
     * back to plain-text similarity otherwise.
     */
    private boolean isDuplicate(List<String> existingFacts, List<double[]> existingVectors,
                                 String candidate, double[] candidateVector) {
        double threshold = plugin.getConfigManager().getEmbeddingsSimilarityThreshold();
        boolean canCompareSemantically = candidateVector != null
                && existingVectors.size() == existingFacts.size();

        String normalizedCandidate = normalize(candidate);
        for (int i = 0; i < existingFacts.size(); i++) {
            String existing = existingFacts.get(i);
            String normalizedExisting = normalize(existing);
            if (normalizedExisting.equals(normalizedCandidate)
                    || normalizedExisting.contains(normalizedCandidate)
                    || normalizedCandidate.contains(normalizedExisting)) {
                return true;
            }
            if (canCompareSemantically && existingVectors.get(i) != null) {
                double similarity = EmbeddingService.cosineSimilarity(candidateVector, existingVectors.get(i));
                if (similarity >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Deletes a player's long-term memory immediately (deliberate action, not batched). */
    public void clear(UUID uuid) {
        cache.remove(uuid);
        embeddingsCache.remove(uuid);
        dirty.remove(uuid);
        File file = fileFor(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("VerityAI: could not delete long-term memory for " + uuid);
        }
    }

    /** Persists every dirty player's facts (+ embeddings) to disk in one batch. Safe to call from an async task. */
    public void flushDirty() {
        if (dirty.isEmpty()) return;
        for (UUID uuid : Set.copyOf(dirty)) {
            List<String> facts = cache.get(uuid);
            if (facts != null) {
                save(uuid, facts, embeddingsCache.getOrDefault(uuid, List.of()));
            }
            dirty.remove(uuid);
        }
    }

    private void save(UUID uuid, List<String> facts, List<double[]> vectors) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("facts", facts);

        List<List<Double>> serializedVectors = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            double[] vec = i < vectors.size() ? vectors.get(i) : null;
            List<Double> row = new ArrayList<>();
            if (vec != null) {
                for (double v : vec) row.add(v);
            }
            serializedVectors.add(row);
        }
        yaml.set("embeddings", serializedVectors);

        try {
            yaml.save(fileFor(uuid));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "VerityAI: failed to save long-term memory for " + uuid, e);
        }
    }
}
