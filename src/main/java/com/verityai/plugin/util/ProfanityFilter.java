package com.verityai.plugin.util;

import com.verityai.plugin.VerityAI;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Word-list based moderation filter. Blocks obviously unsafe or disallowed
 * content before it ever reaches the AI, and can also scrub AI output before
 * it reaches chat.
 *
 * Reads the blocked-word list and enabled flag LIVE from ConfigManager on
 * every call (instead of a copy cached at construction time), so /verity
 * reload picks up edited word lists immediately.
 *
 * Two checks run per word: a word-boundary match on the plain lower-cased
 * text (keeps false positives like a blocked word hiding inside a longer,
 * innocent word to a minimum), and a "compact" check with all punctuation/
 * whitespace stripped out (catches spacing/punctuation evasion tricks like
 * "b a d" or "b.a.d" without mangling normal sentences for the first check).
 */
public class ProfanityFilter {

    private final VerityAI plugin;

    public ProfanityFilter(VerityAI plugin) {
        this.plugin = plugin;
    }

    public boolean isBlocked(String text) {
        var cfg = plugin.getConfigManager();
        List<String> blockedWords = cfg.getBlockedWords();
        if (!cfg.isProfanityFilterEnabled() || text == null || blockedWords == null || blockedWords.isEmpty()) {
            return false;
        }

        String plain = text.toLowerCase(Locale.ROOT);
        String compact = compact(plain);

        for (String word : blockedWords) {
            if (word == null || word.isBlank()) continue;
            String plainWord = word.toLowerCase(Locale.ROOT).trim();
            String compactWord = compact(plainWord);
            if (compactWord.isEmpty()) continue;

            if (isLatin(plainWord)) {
                if (Pattern.compile("\\b" + Pattern.quote(plainWord) + "\\b").matcher(plain).find()) {
                    return true;
                }
            } else if (plain.contains(plainWord)) {
                // Persian/Arabic script doesn't reliably support \b word boundaries in
                // regex, so fall back to a plain substring check for the exact form.
                return true;
            }

            // Evasion check for every script: spaced/punctuated-out letters still match.
            if (compact.contains(compactWord)) {
                return true;
            }
        }
        return false;
    }

    /** Strips everything except letters/digits, so "b.a.d" and "b a d" both become "bad". */
    private String compact(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean isLatin(String text) {
        return text.chars().filter(Character::isLetter)
                .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    }
}
