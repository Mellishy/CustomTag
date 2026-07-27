package com.mellishy.customtag.ai;

/**
 * The ONLY three decisions the AI may ever emit - no other decision states exist anywhere in
 * the AI subsystem.
 */
public enum AIDecisionType {
    APPROVED,
    REJECTED,
    NEEDS_REVIEW
}
