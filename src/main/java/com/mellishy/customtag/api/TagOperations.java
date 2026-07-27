package com.mellishy.customtag.api;

import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransactionType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level operations facade of the public API - the one-line answers to the questions
 * external plugins actually ask ("what tag does this player wear?", "give them 3 tokens",
 * "approve request REQ-000123"), without having to understand the plugin's internal service
 * layout. Obtain it via {@link CustomTagAPI#operations()}; the lower-level services stay
 * available through the other {@link CustomTagAPI} getters for anything not covered here.
 *
 * Every mutation goes through the exact same pipeline as the plugin's own GUI/commands:
 * validated, ledger-logged, audited, fired as a Bukkit event, published to webhooks and
 * broadcast to the other servers of the network. There is deliberately no shortcut around any
 * of that.
 *
 * THREAD SAFETY: all read methods are safe from any thread. Mutations (token changes, request
 * decisions) must run on the main thread and throw {@link IllegalStateException} when called
 * from anywhere else - failing loudly beats corrupting player data quietly.
 */
public interface TagOperations {

    // ---- reads (any thread) ----

    /** The raw (uncolored-markup) text of the player's currently equipped tag, if any. */
    Optional<String> activeTagRaw(UUID player);

    /** The player's currently equipped tag as a legacy color-coded string ("&#xA7;6[VIP]"), or empty. */
    Optional<String> activeTagLegacy(UUID player);

    /** Current token balance. Players unknown to the plugin report 0. */
    int tokenBalance(UUID player);

    /** How many tags (any status) the player owns. */
    int tagCount(UUID player);

    /** The player's permanent custom id, e.g. {@code 3VF-2}. Minted on first call if needed. */
    String customId(UUID player);

    /** The display form of the custom id, e.g. {@code <#3VF-2>}. */
    String customIdDisplay(UUID player);

    /** Reverse lookup: resolves a custom id (with or without the {@code <#...>} wrapper) to a player. */
    Optional<UUID> playerByCustomId(String customId);

    /** Snapshot of every currently open request, in review order (priority, then age). */
    List<TagRequest> openRequests();

    /** Looks a request up by its {@code REQ-XXXXXXXX} id (open or closed). */
    Optional<TagRequest> requestById(String requestId);

    // ---- mutations (main thread only) ----

    /**
     * Changes a token balance through the full pipeline (ledger transaction, audit entry,
     * {@code TokenBalanceChangeEvent}, webhook, cross-server sync). Use
     * {@link TokenTransactionType#ADMIN_GIVE}/{@link TokenTransactionType#ADMIN_TAKE} for staff
     * actions, {@link TokenTransactionType#PURCHASE} for store integrations and
     * {@link TokenTransactionType#REWARD} for event/quest plugins - the type is recorded on the
     * transaction and visible in {@code /customtag history}.
     */
    TokenService.Result applyTokens(UUID player, TokenTransactionType type, int amount,
                                    String reason, String actorName);

    /**
     * Approves an open request by id - identical side effects to a staff approval, recorded
     * with the {@code API} actor so audits always show it came from an integration.
     *
     * @return true when the request existed, was still open, and is now approved.
     */
    boolean approveRequest(String requestId, String actorName);

    /**
     * Rejects an open request by id. {@code refund} decides whether the submission token goes
     * back to the player.
     *
     * @return true when the request existed, was still open, and is now rejected.
     */
    boolean rejectRequest(String requestId, String actorName, String reason, boolean refund);
}
