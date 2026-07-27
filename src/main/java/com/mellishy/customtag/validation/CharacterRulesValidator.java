package com.mellishy.customtag.validation;

import java.util.regex.Pattern;

/**
 * Character-level rules from blacklist/settings.yml: minimum visible length and an optional
 * whole-text allowed-characters pattern (e.g. restrict tags to Latin letters + digits + a few
 * symbols). Maximum length is deliberately NOT here - it stays in TagService where the
 * per-role override from permissions/roles.yml is applied.
 */
public final class CharacterRulesValidator implements TagValidator {

    private final int minLength;
    private final Pattern allowedPattern; // null = any characters allowed
    private final String tooShortMessage;
    private final String invalidCharsMessage;

    public CharacterRulesValidator(int minLength, String allowedRegex,
                                   String tooShortMessage, String invalidCharsMessage) {
        this.minLength = Math.max(0, minLength);
        this.allowedPattern = allowedRegex == null || allowedRegex.isBlank()
                ? null
                : Pattern.compile(allowedRegex);
        this.tooShortMessage = tooShortMessage;
        this.invalidCharsMessage = invalidCharsMessage;
    }

    @Override
    public String name() {
        return "characters";
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        String plain = input.plainText();
        if (plain.length() < minLength) {
            return new ValidationResult(ValidationVerdict.REJECT, name(), "too-short", tooShortMessage, true);
        }
        if (allowedPattern != null && !allowedPattern.matcher(plain).matches()) {
            return new ValidationResult(ValidationVerdict.REJECT, name(), "invalid-characters", invalidCharsMessage, true);
        }
        return ValidationResult.allow();
    }
}
