package com.mellishy.customtag.audit;

/**
 * Independent audit-trail categories - each entry carries one so staff can
 * filter "only security events", "only token movements", etc.
 */
public enum AuditCategory {
    REQUEST,
    TOKEN,
    AI,
    STAFF,
    SECURITY,
    WEBHOOK,
    SYSTEM
}
