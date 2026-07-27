package com.mellishy.customtag.token;

import java.util.UUID;

/**
 * One immutable ledger row - no transaction ever occurs without being persisted. Ids use the
 * {@code TOKEN-00000001} format: unique, permanent, searchable.
 *
 * @param transactionId  TOKEN-XXXXXXXX, never reused
 * @param playerUuid     owner of the balance
 * @param playerName     name at transaction time (for human-readable logs)
 * @param playerCustomId permanent custom id, e.g. {@code <#3VF-2>}
 * @param type           what kind of change this was
 * @param amount         signed delta actually applied (e.g. -1 for a consume)
 * @param balanceAfter   balance right after applying
 * @param reason         why (request id, staff command, store order id, ...)
 * @param actorName      who performed it (player/staff/AI/store name)
 * @param at             epoch millis
 */
public record TokenTransaction(String transactionId, UUID playerUuid, String playerName,
                               String playerCustomId, TokenTransactionType type, int amount,
                               int balanceAfter, String reason, String actorName, long at) {}
