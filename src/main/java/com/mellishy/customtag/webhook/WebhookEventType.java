package com.mellishy.customtag.webhook;

/**
 * Every event the integration layer can broadcast to external services. Each configured
 * endpoint subscribes to a subset of these.
 */
public enum WebhookEventType {
    REQUEST_CREATED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    REQUEST_CANCELLED,
    REQUEST_EXPIRED,
    REQUEST_REOPENED,
    AI_DECISION,
    AI_REVIEW_REQUIRED,
    TOKEN_TRANSACTION,
    QUEUE_FULL,
    SECURITY_ALERT,
    MAINTENANCE
}
