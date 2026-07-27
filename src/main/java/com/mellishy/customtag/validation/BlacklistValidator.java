package com.mellishy.customtag.validation;

import java.util.List;
import java.util.Set;

/**
 * Category-based word blacklist: every category carries its own action and
 * refund policy - e.g. profanity rejects WITHOUT refund, reserved rank names reject WITH refund,
 * a custom "suspicious" category escalates to AI review. Matching runs against the
 * {@link TextNormalizer normalized} text, so spacing/leet/lookalike bypasses land on the same
 * entries as the plain word.
 */
public final class BlacklistValidator implements TagValidator {

    /** How a category's words are compared against the normalized tag. */
    public enum MatchMode { CONTAINS, EXACT }

    /**
     * One admin-defined category from blacklist/words.yml.
     *
     * @param name    category id used in logs/audit
     * @param verdict action when a word of this category matches
     * @param refund  whether the token is returned on a resulting rejection
     * @param mode    contains vs exact matching
     * @param words   pre-normalized word list
     * @param message player-facing reason (null = module default)
     */
    public record Category(String name, ValidationVerdict verdict, boolean refund,
                           MatchMode mode, Set<String> words, String message) {

        /** Normalizes the configured words once at load time so matching is a plain set/substring check. */
        public static Category of(String name, ValidationVerdict verdict, boolean refund,
                                  MatchMode mode, List<String> rawWords, String message) {
            Set<String> normalized = new java.util.HashSet<>();
            for (String w : rawWords) {
                String n = TextNormalizer.normalize(w);
                if (!n.isEmpty()) normalized.add(n);
            }
            return new Category(name, verdict, refund, mode, Set.copyOf(normalized), message);
        }
    }

    private final List<Category> categories;

    public BlacklistValidator(List<Category> categories) {
        this.categories = List.copyOf(categories);
    }

    @Override
    public String name() {
        return "blacklist";
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        String normalized = input.normalizedText();
        if (normalized.isEmpty()) return ValidationResult.allow();
        for (Category category : categories) {
            for (String word : category.words()) {
                boolean hit = category.mode() == MatchMode.EXACT
                        ? normalized.equals(word)
                        : normalized.contains(word);
                if (hit) {
                    return new ValidationResult(category.verdict(), name(), category.name(),
                            category.message(), category.refund());
                }
            }
        }
        return ValidationResult.allow();
    }
}
