package com.mellishy.customtag.validation;

/**
 * One step of the validation pipeline. Implementations are immutable and rebuilt from config on
 * every (re)load, so validating is pure, lock-free and safe from any thread.
 */
public interface TagValidator {

    /** Stable identifier used in logs, audit entries and webhook payloads. */
    String name();

    /** Returns {@link ValidationResult#allow()} when this validator has no objection. */
    ValidationResult validate(ValidationInput input);
}
