package com.mellishy.customtag.validation;

/**
 * What the validation pipeline decided should happen to a submission - every rule/category
 * maps to exactly one of these actions.
 */
public enum ValidationVerdict {
    /** Nothing matched - the submission may continue down the pipeline (queue, AI, ...). */
    ALLOW,
    /** Hard reject before the queue - the tag is never created. */
    REJECT,
    /** Suspicious but not conclusive - let the AI moderation system decide. */
    AI_REVIEW,
    /** Needs a human - skip AI and put it straight in the staff review queue. */
    STAFF_REVIEW
}
