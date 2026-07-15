package com.verityai.plugin.ai;

import com.verityai.plugin.VerityAI;

/**
 * Thin wrapper around OpenRouterClient.embed(), gated by ai.embeddings.enabled.
 * Used for semantic (meaning-based) matching of long-term memory facts instead
 * of plain text comparison — e.g. "I love building castles" and "my favorite
 * thing to build is castles" should be recognized as near-duplicates, and a
 * relevant fact should surface even if it doesn't share exact keywords with
 * the player's current question.
 *
 * Fully optional: if disabled, misconfigured, or a call fails, callers fall
 * back to the existing plain-text similarity/relevance logic — embeddings are
 * a quality upgrade, never a hard requirement.
 */
public class EmbeddingService {

    private final VerityAI plugin;

    public EmbeddingService(VerityAI plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfigManager().isEmbeddingsEnabled();
    }

    /** Returns null (rather than throwing) on any failure, so callers can gracefully fall back. */
    public double[] embedOrNull(String text) {
        if (!isEnabled() || text == null || text.isBlank()) {
            return null;
        }
        try {
            return plugin.getAIHandler().getClient().embed(text);
        } catch (Exception e) {
            plugin.getDebugLogger().debug("VerityAI: embedding request failed: " + e.getMessage());
            return null;
        }
    }

    public static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return -1;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
