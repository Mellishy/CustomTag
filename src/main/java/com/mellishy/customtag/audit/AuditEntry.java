package com.mellishy.customtag.audit;

/**
 * One immutable audit-trail row. Persisted as one JSON line per entry, so the files are both
 * machine-parseable (JSONL - trivially greppable and exportable) and safely appendable.
 *
 * @param at             epoch millis
 * @param category       which independent log this belongs to
 * @param action         short verb, e.g. "approve", "reject", "refund", "ai-decision"
 * @param actorName      who did it (player/staff name, AI provider, "system")
 * @param targetName     who it affected (may be null for system events)
 * @param targetCustomId permanent custom id of the target, e.g. {@code <#3VF-2>} (may be null)
 * @param requestId      related {@code REQ-...} id (may be null)
 * @param detail         free-form context - reasons, amounts, previous state
 */
public record AuditEntry(long at, AuditCategory category, String action, String actorName,
                         String targetName, String targetCustomId, String requestId, String detail) {}
