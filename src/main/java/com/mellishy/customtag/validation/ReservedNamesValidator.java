package com.mellishy.customtag.validation;

import java.util.List;
import java.util.Set;

/**
 * Reserved tag protection: staff rank names (Owner, Admin, Developer,
 * ...), server/brand names and any custom additions can never be claimed as a tag. Matches on
 * the normalized text so {@code [ΟWNER]}, {@code o.w.n.e.r} and {@code 0wner} are all caught.
 * Default action: reject WITH refund (an honest "that name is
 * taken" - unlike profanity, which is penalized without refund by the blacklist categories).
 */
public final class ReservedNamesValidator implements TagValidator {

    private final Set<String> reservedExact;
    private final Set<String> reservedContains;
    private final ValidationVerdict verdict;
    private final boolean refund;
    private final String message;

    /**
     * @param exactNames    names matched when the whole normalized tag IS the name ("king" tag vs "king" reserved)
     * @param containsNames names matched when they appear ANYWHERE in the normalized tag
     */
    public ReservedNamesValidator(List<String> exactNames, List<String> containsNames,
                                  ValidationVerdict verdict, boolean refund, String message) {
        this.reservedExact = normalize(exactNames);
        this.reservedContains = normalize(containsNames);
        this.verdict = verdict;
        this.refund = refund;
        this.message = message;
    }

    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new java.util.HashSet<>();
        for (String s : raw) {
            String n = TextNormalizer.normalize(s);
            if (!n.isEmpty()) out.add(n);
        }
        return Set.copyOf(out);
    }

    @Override
    public String name() {
        return "reserved";
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        String normalized = input.normalizedText();
        if (normalized.isEmpty()) return ValidationResult.allow();
        if (reservedExact.contains(normalized)) {
            return new ValidationResult(verdict, name(), "reserved-exact", message, refund);
        }
        for (String word : reservedContains) {
            if (normalized.contains(word)) {
                return new ValidationResult(verdict, name(), "reserved-contains", message, refund);
            }
        }
        return ValidationResult.allow();
    }
}
