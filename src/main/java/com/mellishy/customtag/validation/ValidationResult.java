package com.mellishy.customtag.validation;

/**
 * Outcome of one validator (or of the whole pipeline - the first non-ALLOW result wins).
 *
 * @param verdict   what should happen to the submission
 * @param validator which validator produced this result (for logs/audit)
 * @param category  the rule category that matched (e.g. "profanity", "reserved-rank"), may be null
 * @param message   admin-configured player-facing reason, may be null (a default is used)
 * @param refund    whether the reserved token should be returned when this leads to a rejection
 */
public record ValidationResult(ValidationVerdict verdict, String validator, String category,
                               String message, boolean refund) {

    private static final ValidationResult ALLOW = new ValidationResult(ValidationVerdict.ALLOW, null, null, null, true);

    public static ValidationResult allow() {
        return ALLOW;
    }

    public boolean isAllowed() {
        return verdict == ValidationVerdict.ALLOW;
    }
}
