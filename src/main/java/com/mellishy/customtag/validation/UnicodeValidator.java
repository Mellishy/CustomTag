package com.mellishy.customtag.validation;

/**
 * Blocks invisible / zero-width / bidi-control characters outright.
 * These have exactly one use in a tag: hiding content from admins and
 * the blacklist, so the default action is a hard, refunded reject.
 */
public final class UnicodeValidator implements TagValidator {

    private final ValidationVerdict verdict;
    private final boolean refund;
    private final String message;

    public UnicodeValidator(ValidationVerdict verdict, boolean refund, String message) {
        this.verdict = verdict;
        this.refund = refund;
        this.message = message;
    }

    @Override
    public String name() {
        return "unicode";
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        if (TextNormalizer.containsInvisible(input.plainText())) {
            return new ValidationResult(verdict, name(), "invisible-characters", message, refund);
        }
        return ValidationResult.allow();
    }
}
