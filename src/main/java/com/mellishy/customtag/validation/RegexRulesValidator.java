package com.mellishy.customtag.validation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Admin-defined regex rules from blacklist/regex.yml - "only numbers", "repeated characters",
 * "reserved bracket formats" and any custom pattern, each with its own action and refund policy.
 */
public final class RegexRulesValidator implements TagValidator {

    /** Which derived form of the tag the pattern is tested against. */
    public enum Target { PLAIN, RAW, NORMALIZED }

    /**
     * One compiled rule.
     *
     * @param name    rule id used in logs/audit
     * @param pattern compiled pattern - a FIND anywhere in the target counts as a match
     * @param target  which text form to test
     * @param verdict action on match
     * @param refund  refund policy on a resulting rejection
     * @param message player-facing reason (null = module default)
     */
    public record Rule(String name, Pattern pattern, Target target, ValidationVerdict verdict,
                       boolean refund, String message) {}

    private final List<Rule> rules;

    public RegexRulesValidator(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public String name() {
        return "regex";
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        for (Rule rule : rules) {
            String target = switch (rule.target()) {
                case PLAIN -> input.plainText();
                case RAW -> input.rawText();
                case NORMALIZED -> input.normalizedText();
            };
            if (rule.pattern().matcher(target).find()) {
                return new ValidationResult(rule.verdict(), name(), rule.name(), rule.message(), rule.refund());
            }
        }
        return ValidationResult.allow();
    }
}
