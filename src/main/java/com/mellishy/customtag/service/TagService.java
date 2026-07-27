package com.mellishy.customtag.service;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.ai.AIDecision;
import com.mellishy.customtag.ai.AIDecisionType;
import com.mellishy.customtag.ai.AIModerationService;
import com.mellishy.customtag.api.event.AIDecisionEvent;
import com.mellishy.customtag.api.event.TagRequestApprovedEvent;
import com.mellishy.customtag.api.event.TagRequestCreatedEvent;
import com.mellishy.customtag.api.event.TagRequestRejectedEvent;
import com.mellishy.customtag.audit.AuditCategory;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.event.AdminRejectEvent;
import com.mellishy.customtag.event.TagSubmitEvent;
import com.mellishy.customtag.perm.RoleDefinition;
import com.mellishy.customtag.platform.PlatformServices;
import com.mellishy.customtag.request.DecisionActor;
import com.mellishy.customtag.request.RequestStatus;
import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.security.SecurityService;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransactionType;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.validation.TextNormalizer;
import com.mellishy.customtag.validation.ValidationResult;
import com.mellishy.customtag.validation.ValidationVerdict;
import com.mellishy.customtag.webhook.WebhookEventType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Central home of every business rule in the plugin: single-pending-request lock, token economy
 * (reservation-based AND ledger-backed - every balance change is a logged transaction),
 * cooldowns, auto-priority of newly approved tags, refunds on deletion, etc. GUIs, listeners
 * and commands call the public methods here instead of touching PlayerData directly, so the
 * rules only ever live in one place.
 *
 * ---- The full submission pipeline ----
 * reserve (token CONSUME) -> security gate (rate limit, duplicates, maintenance) ->
 * validation pipeline (unicode, characters, reserved names, blacklist, regex - free, before any
 * AI cost) -> global request queue (REQ-XXXXXXXX id, capacity, priority) -> AI moderation
 * (optional; approve/reject/escalate by confidence) -> staff review. Every stage refunds or
 * keeps the token according to the configured policy, and every outcome is audited, fired as a
 * Bukkit event and published to the webhook layer.
 *
 * ---- Token reservation flow (dupe-proof leave/death handling) ----
 * A token is taken the moment a player commits to a creation method (opens the book or
 * starts chat input) via {@link #reserveForCreation(Player)}, NOT when they finally submit.
 * That reservation is stamped with a random id ({@link PlayerData#getReservationId()}).
 *  - On successful submit, the reservation is simply cleared (the token was already spent).
 *  - On explicit cancel (typing "cancel", clicking Back), the reservation is refunded.
 *  - On disconnect or losing the book to death, the reservation is refunded automatically
 *    and the player is told to click a message to resume next time they're online.
 *  - Every creation book is stamped with the reservation id it was created for. If a player
 *    manages to hang on to an old book after their reservation was refunded/cleared, signing
 *    it later is rejected because the id no longer matches - this is what prevents
 *    duplicating tokens by leaving mid-creation and reusing a stale book.
 */
public class TagService implements Listener {

    private final MellishyCustomTag plugin;

    public TagService(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    private PlatformServices platform() {
        return plugin.platform();
    }

    // ---------- submission (chat or book, new or edit) ----------

    @EventHandler
    public void onSubmit(TagSubmitEvent event) {
        Player player = event.getPlayer();
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        // Re-entrancy guard: one player can never have two submission flows mutating their
        // data at the same time (double-click, packet spam, plugin race).
        if (!platform().security().tryAcquireOperationLock(player.getUniqueId())) {
            return;
        }
        try {
            if (event.getEditingTagId() == null) {
                submitNew(player, data, cfg, event.getRawText(), event.getReservationId());
            } else {
                submitEdit(player, data, cfg, event.getEditingTagId(), event.getRawText());
            }
        } finally {
            platform().security().releaseOperationLock(player.getUniqueId());
        }
    }

    private void submitNew(Player player, PlayerData data, ConfigManager cfg, String rawText, String reservationId) {
        boolean valid = data.isReservationActive()
                && reservationId != null
                && reservationId.equals(data.getReservationId());
        if (!valid) {
            // their reservation was already refunded (e.g. they disconnected and rejoined) - reject silently-ish
            msg(player, cfg.msg("reservation-expired"));
            return;
        }
        // Defense-in-depth: ChatInputListener and BookEditListener already reject an oversized tag
        // before ever getting here, but TagService is the single real trust boundary for the whole
        // plugin (every path that can create a tag funnels through TagSubmitEvent into this method) -
        // it must never assume a caller upstream already validated this, in case a future creation
        // method or an external plugin ever calls callEvent(new TagSubmitEvent(...)) directly.
        if (!isWithinLengthLimit(player, cfg, rawText)) return;

        // ---- security gate (rate limit / duplicate / maintenance) - reservation stays active so
        // the token is never lost; the player can retry with different text or cancel for a refund
        SecurityService.Block block = platform().security().checkSubmission(player.getUniqueId(), rawText);
        switch (block) {
            case MAINTENANCE -> {
                msg(player, cfg.msg("maintenance-active"));
                return;
            }
            case RATE_LIMITED -> {
                long wait = platform().security().submissionRetryAfterSeconds(player.getUniqueId());
                msg(player, cfg.msg("rate-limited").replace("{time}", String.valueOf(Math.max(1, wait))));
                platform().audit().log(AuditCategory.SECURITY, "rate-limited", player.getName(),
                        player.getName(), platform().playerIds().display(player.getUniqueId()), null, null);
                return;
            }
            case DUPLICATE -> {
                msg(player, cfg.msg("duplicate-submission"));
                return;
            }
            case NONE -> { /* proceed */ }
        }

        // ---- validation pipeline (free - runs BEFORE any queue slot or AI cost is spent) ----
        ValidationResult vr = platform().validation().validate(player.getName(), rawText);
        if (vr.verdict() == ValidationVerdict.REJECT) {
            handleValidationReject(player, data, rawText, vr);
            return;
        }

        // ---- queue capacity + per-role pending limit ----
        RoleDefinition role = platform().permissions().roleOf(player);
        if (role.maxPending() > 0
                && platform().requests().openCountOf(player.getUniqueId()) >= role.maxPending()) {
            msg(player, cfg.msg("request-pending-block"));
            return;
        }
        if (platform().requests().isQueueFull()) {
            refuseQueueFull(player, data);
            return;
        }

        TagEntry entry = new TagEntry(UUID.randomUUID().toString(), player.getUniqueId(), rawText, TagStatus.PENDING, System.currentTimeMillis());
        Optional<TagRequest> created = platform().requests().create(
                player.getUniqueId(), player.getName(),
                platform().playerIds().idFor(player.getUniqueId()),
                entry.getId(), rawText, ColorUtil.stripToPlain(rawText),
                platform().serverName(), platform().priorityFor(role));
        if (created.isEmpty()) {
            // capacity race - two submissions fought for the last slot and this one lost
            refuseQueueFull(player, data);
            return;
        }
        TagRequest request = created.get();

        data.getTags().add(entry);
        // the token backing this tag was already deducted when the reservation was made - just clear it now
        data.setReservationActive(false);
        data.setReservationId(null);
        plugin.data().save(data);
        msg(player, cfg.msg("request-submitted"));
        playSound(player, "submit", "ui.button.click");

        platform().audit().log(AuditCategory.REQUEST, "created", player.getName(), player.getName(),
                request.getPlayerCustomId(), request.getRequestId(), request.getPlainText());
        Bukkit.getPluginManager().callEvent(new TagRequestCreatedEvent(
                player.getUniqueId(), player.getName(), request.getRequestId(), rawText));
        platform().webhooks().publish(WebhookEventType.REQUEST_CREATED, platform().requestPayload(request));

        routeAfterQueueEntry(request, vr);
    }

    /**
     * What happens to a freshly queued request next: straight to staff, straight to the AI, or
     * escalated because validation demanded a specific review path.
     */
    private void routeAfterQueueEntry(TagRequest request, ValidationResult vr) {
        if (vr.verdict() == ValidationVerdict.STAFF_REVIEW) {
            platform().requests().escalateToStaff(request.getRequestId(),
                    "validation", vr.validator() == null ? "-" : vr.validator(), -1,
                    "flagged by validation" + (vr.category() != null ? " (" + vr.category() + ")" : ""));
            platform().webhooks().publish(WebhookEventType.AI_REVIEW_REQUIRED, platform().requestPayload(request));
            return;
        }
        if (platform().ai().isEnabled()) {
            startAiModeration(request);
        } else if (vr.verdict() == ValidationVerdict.AI_REVIEW) {
            // validation wanted an AI double-check but AI is off - fail safe to staff review
            platform().requests().escalateToStaff(request.getRequestId(),
                    "validation", vr.validator() == null ? "-" : vr.validator(), -1,
                    "AI review requested but AI is disabled");
        }
        // plain ALLOW with AI disabled -> stays PENDING for normal staff review
    }

    /**
     * Pre-queue rejection by the validation pipeline: the request never enters the queue and
     * never costs an AI call. The token refund follows the matched rule's policy.
     */
    private void handleValidationReject(Player player, PlayerData data, String rawText, ValidationResult vr) {
        data.setReservationActive(false);
        data.setReservationId(null);
        if (vr.refund()) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1,
                    "validation-reject" + (vr.category() != null ? ":" + vr.category() : ""), "validation");
        }
        plugin.data().save(data);

        String message = platform().validation().messageFor(vr);
        msg(player, plugin.config().raw().getString("messages.prefix", "") + message);
        playSound(player, "reject", "entity.villager.no");

        platform().security().flag(player.getUniqueId(),
                "validation" + (vr.category() != null ? ":" + vr.category() : ""));
        platform().audit().log(AuditCategory.SECURITY, "validation-reject", "validation",
                player.getName(), platform().playerIds().display(player.getUniqueId()), null,
                (vr.validator() == null ? "?" : vr.validator())
                        + (vr.category() != null ? "/" + vr.category() : "")
                        + " text=" + ColorUtil.stripToPlain(rawText)
                        + " refund=" + vr.refund());
        Bukkit.getPluginManager().callEvent(new TagRequestRejectedEvent(
                player.getUniqueId(), null, rawText, message, vr.refund(),
                DecisionActor.SYSTEM, vr.validator() == null ? "validation" : vr.validator()));
        platform().webhooks().publish(WebhookEventType.SECURITY_ALERT, Map.of(
                "player", player.getName(),
                "custom-id", platform().playerIds().display(player.getUniqueId()),
                "tag", ColorUtil.stripToPlain(rawText),
                "reason", (vr.validator() == null ? "validation" : vr.validator())
                        + (vr.category() != null ? " (" + vr.category() + ")" : "")));
    }

    /** Queue full: refund + clear the reservation so the token is never stranded, tell everyone. */
    private void refuseQueueFull(Player player, PlayerData data) {
        data.setReservationActive(false);
        data.setReservationId(null);
        platform().applyTokens(data, TokenTransactionType.REFUND, 1, "queue-full", "system");
        plugin.data().save(data);
        msg(player, plugin.config().msg("queue-full"));
        platform().webhooks().publish(WebhookEventType.QUEUE_FULL, Map.of(
                "player", player.getName(),
                "limit", String.valueOf(platform().requests().globalPendingLimit())));
    }

    private void submitEdit(Player player, PlayerData data, ConfigManager cfg, String tagId, String rawText) {
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty()) return;
        TagEntry tag = opt.get();

        boolean otherPending = data.getTags().stream().anyMatch(t -> t.getStatus() == TagStatus.PENDING && !t.getId().equals(tagId));
        if (otherPending) {
            msg(player, cfg.msg("request-pending-block"));
            return;
        }
        // see the matching comment in submitNew() - same defense-in-depth reasoning applies to edits.
        if (!isWithinLengthLimit(player, cfg, rawText)) return;

        // Edits pass the same security gate and validation pipeline as new submissions - but a
        // block/reject leaves the existing tag completely untouched (nothing to refund: edits
        // never cost a token).
        SecurityService.Block block = platform().security().checkSubmission(player.getUniqueId(), rawText);
        if (block != SecurityService.Block.NONE) {
            switch (block) {
                case MAINTENANCE -> msg(player, cfg.msg("maintenance-active"));
                case RATE_LIMITED -> msg(player, cfg.msg("rate-limited").replace("{time}",
                        String.valueOf(Math.max(1, platform().security().submissionRetryAfterSeconds(player.getUniqueId())))));
                case DUPLICATE -> msg(player, cfg.msg("duplicate-submission"));
                default -> { }
            }
            return;
        }
        ValidationResult vr = platform().validation().validate(player.getName(), rawText);
        if (vr.verdict() == ValidationVerdict.REJECT) {
            msg(player, cfg.raw().getString("messages.prefix", "") + platform().validation().messageFor(vr));
            platform().security().flag(player.getUniqueId(),
                    "validation" + (vr.category() != null ? ":" + vr.category() : ""));
            return;
        }

        // an edit restarts moderation: retire the request that tracked the OLD text (if any is
        // still open) and queue a fresh one for the new text, keeping ids and history clean
        platform().requests().byTag(player.getUniqueId(), tagId).ifPresent(old ->
                platform().requests().cancel(old.getRequestId(), player.getName() + " (edited)"));

        tag.setRawText(rawText);
        tag.setStatus(TagStatus.PENDING);
        tag.setRejectReason(null);

        RoleDefinition role = platform().permissions().roleOf(player);
        Optional<TagRequest> created = platform().requests().create(
                player.getUniqueId(), player.getName(),
                platform().playerIds().idFor(player.getUniqueId()),
                tagId, rawText, ColorUtil.stripToPlain(rawText),
                platform().serverName(), platform().priorityFor(role));

        plugin.data().save(data);
        msg(player, cfg.msg("edit-submitted"));
        playSound(player, "submit", "ui.button.click");

        created.ifPresent(request -> {
            platform().audit().log(AuditCategory.REQUEST, "edited", player.getName(), player.getName(),
                    request.getPlayerCustomId(), request.getRequestId(), request.getPlainText());
            Bukkit.getPluginManager().callEvent(new TagRequestCreatedEvent(
                    player.getUniqueId(), player.getName(), request.getRequestId(), rawText));
            platform().webhooks().publish(WebhookEventType.REQUEST_CREATED, platform().requestPayload(request));
            routeAfterQueueEntry(request, vr);
        });
        // queue full during an edit: the tag simply stays PENDING without a queue record - staff
        // still see it in the per-player admin view, nothing is lost
    }

    /** Shared length check backing the defense-in-depth guards in {@link #submitNew} and {@link #submitEdit}. */
    private boolean isWithinLengthLimit(Player player, ConfigManager cfg, String rawText) {
        int plainLength = ColorUtil.stripToPlain(rawText).length();
        int maxLength = cfg.maxTagLength();
        // per-role override from permissions/roles.yml (VIPs may get longer tags than default)
        RoleDefinition role = platform().permissions().roleOf(player);
        if (role.maxTagLength() > 0) {
            maxLength = role.maxTagLength();
        }
        if (plainLength > maxLength) {
            msg(player, cfg.msg("tag-too-long")
                    .replace("{length}", String.valueOf(plainLength))
                    .replace("{max}", String.valueOf(maxLength)));
            return false;
        }
        return true;
    }

    /** The effective max-tags limit for this player: role override, else the config.yml default. */
    private int effectiveMaxTags(PlayerData data) {
        Player online = Bukkit.getPlayer(data.getUuid());
        if (online != null) {
            RoleDefinition role = platform().permissions().roleOf(online);
            if (role.maxTags() >= 0) return role.maxTags();
        }
        return plugin.config().maxTagsPerPlayer();
    }

    // ---------- AI moderation ----------

    private void startAiModeration(TagRequest request) {
        platform().requests().markProcessing(request.getRequestId(), DecisionActor.AI, "ai");
        platform().ai().moderate(request.getPlainText(), TextNormalizer.normalize(request.getPlainText()),
                outcome -> handleAiOutcome(request.getRequestId(), outcome));
    }

    /** Runs on the main thread (the AI service delivers callbacks through the plugin scheduler). */
    private void handleAiOutcome(String requestId, AIModerationService.Outcome outcome) {
        Optional<TagRequest> current = platform().requests().byId(requestId);
        // staff may have decided the request while the API call was in flight - their decision wins
        if (current.isEmpty() || !current.get().getStatus().isOpen()) return;
        TagRequest request = current.get();
        AIDecision decision = outcome.decision();

        platform().requests().attachAiResult(requestId, decision.provider(), decision.model(),
                decision.confidence(), decision.reason());
        Bukkit.getPluginManager().callEvent(new AIDecisionEvent(request.getPlayerUuid(), requestId, decision));
        platform().audit().log(AuditCategory.AI, "decision", decision.provider(),
                request.getPlayerName(), request.getPlayerCustomId(), requestId,
                decision.type() + " confidence=" + decision.confidence()
                        + (outcome.fromCache() ? " (cached)" : "")
                        + " reason=" + decision.reason());
        Map<String, String> payload = platform().requestPayload(request);
        payload.put("ai-decision", decision.type().name());
        payload.put("ai-confidence", String.valueOf(decision.confidence()));
        payload.put("ai-reason", decision.reason() == null ? "-" : decision.reason());
        platform().webhooks().publish(WebhookEventType.AI_DECISION, payload);

        // SUGGEST mode or any failure means the AI never decides on its own (fail-safe)
        boolean autonomous = platform().ai().mode() == AIModerationService.Mode.FULL && !outcome.failed();
        if (autonomous && decision.type() == AIDecisionType.APPROVED) {
            approveByRequest(request, DecisionActor.AI, decision.provider());
        } else if (autonomous && decision.type() == AIDecisionType.REJECTED) {
            rejectByRequest(request, DecisionActor.AI, decision.provider(),
                    decision.reason() == null || decision.reason().isBlank()
                            ? plugin.config().rawMsg("ai-rejected-reason")
                            : decision.reason(),
                    platform().refundOnAiReject());
        } else {
            platform().requests().escalateToStaff(requestId, decision.provider(), decision.model(),
                    decision.confidence(), decision.reason());
            platform().webhooks().publish(WebhookEventType.AI_REVIEW_REQUIRED, platform().requestPayload(request));
        }
    }

    // ---------- token reservation (see class javadoc) ----------

    /**
     * Attempts to reserve (spend) exactly one token for a brand-new tag creation attempt.
     * Returns the new reservation id on success, sends the player the appropriate error
     * message and returns null on failure. Safe to call again while a reservation is
     * already active (e.g. re-entering the method chooser) - it just returns the existing id
     * instead of charging a second token.
     */
    public String reserveForCreation(Player player) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        if (data.isReservationActive()) {
            return data.getReservationId();
        }
        if (platform().security().isUnderMaintenance("submissions")) {
            msg(player, cfg.msg("maintenance-active"));
            return null;
        }
        if (platform().tokens().isFrozen(player.getUniqueId())) {
            msg(player, cfg.msg("account-frozen"));
            return null;
        }
        if (data.hasPending()) {
            msg(player, cfg.msg("request-pending-block"));
            return null;
        }
        if (plugin.cooldown().isOnCooldown(data)) {
            long secs = plugin.cooldown().remainingSeconds(data);
            msg(player, cfg.msg("cooldown-active").replace("{time}", plugin.cooldown().formatDuration(secs)));
            return null;
        }
        if (data.getTokens() <= 0) {
            msg(player, cfg.msg("no-tokens"));
            return null;
        }
        // NOTE: activeTagCount() (PENDING + APPROVED), not getTags().size() - a rejected tag is
        // kept only as visible history and must never permanently block new creation. See
        // PlayerData#activeTagCount for the full reasoning.
        int maxTags = effectiveMaxTags(data);
        if (data.activeTagCount() >= maxTags) {
            msg(player, cfg.msg("max-tags-reached").replace("{max}", String.valueOf(maxTags)));
            return null;
        }
        // refuse BEFORE charging when the network queue is full - a token must never be taken
        // for a submission that has no chance of entering the queue
        if (platform().requests().isQueueFull()) {
            msg(player, cfg.msg("queue-full"));
            return null;
        }

        TokenService.Result result = platform().applyTokens(data, TokenTransactionType.CONSUME, 1,
                "tag-creation-reservation", player.getName());
        if (!(result instanceof TokenService.Result.Success)) {
            msg(player, cfg.msg(result instanceof TokenService.Result.Frozen ? "account-frozen" : "no-tokens"));
            return null;
        }
        String id = UUID.randomUUID().toString();
        data.setReservationActive(true);
        data.setReservationId(id);
        plugin.data().save(data);
        return id;
    }

    /** Explicit, player-initiated cancel (Back button, typing "cancel"). Refunds if a reservation is active. No-op otherwise. */
    public void cancelReservation(Player player) {
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        if (releaseReservation(data, "creation-cancelled")) {
            plugin.data().save(data);
        }
    }

    /** @return true if a reservation was actually active and has now been refunded+cleared. */
    private boolean releaseReservation(PlayerData data, String reason) {
        if (data.isReservationActive()) {
            data.setReservationActive(false);
            data.setReservationId(null);
            TokenService.Result result = platform().applyTokens(data, TokenTransactionType.REFUND, 1,
                    reason, "system");
            if (!(result instanceof TokenService.Result.Success)) {
                // the account was frozen mid-flow - the reservation is still cleared (the flow is
                // over) and the blocked refund is on record for staff to settle after unfreezing
                platform().audit().log(AuditCategory.TOKEN, "refund-blocked", "system",
                        data.getLastKnownName(), platform().playerIds().display(data.getUuid()), null,
                        "reservation refund blocked (" + reason + ") - account frozen");
            }
            return true;
        }
        return false;
    }

    /**
     * Called from {@link com.mellishy.customtag.listener.PlayerQuitListener} on quit.
     * If the player had a token reserved for an in-progress creation (book handed out or
     * awaiting chat input), refund it immediately and queue a clickable "resume" message for
     * their next login, rather than leaving the token in limbo or letting a stale book be
     * signed later for a free tag.
     */
    public void handleDisconnect(Player player) {
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        // Always clear both chat-input state maps, not just when isAwaiting() is true: a book-flow
        // preview (see ChatInputListener#showBookPreview) is never added to the `awaiting` map (the
        // player isn't being chat-intercepted, they'd request a new book instead), only to
        // `previews`. Gating this on isAwaiting() left that entry in the previews map forever for
        // any player who disconnected after generating a book preview without confirming/cancelling
        // it - a real, unbounded (well, bounded only by total unique player count) memory leak on a
        // long-running large server. cancel() is a no-op if nothing was pending, so this is always safe.
        plugin.chatInput().cancel(player);
        if (releaseReservation(data, "left-mid-creation")) {
            data.setPendingNotice(plugin.config().rawMsg("left-mid-creation"));
            data.setPendingNoticeResume(true);
        }
        plugin.data().save(data);
    }

    /**
     * Called from {@link com.mellishy.customtag.listener.BookEditListener} whenever a creation book
     * stops existing without ever being submitted - the player died holding it (the book is stripped
     * from their drops so it can't be picked up by someone else), or they threw it away. Since the
     * book itself is gone, refund the reservation immediately and tell the still-online player they
     * can start again.
     *
     * @param reason short machine-ish tag recorded on the refund transaction / audit entry.
     */
    public void handleBookDiscarded(Player player, String reason) {
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        if (releaseReservation(data, reason)) {
            plugin.data().save(data);
            msg(player, plugin.config().msg("creation-lost-refunded"));
        }
    }

    // ---------- player-initiated actions ----------

    public void selectTag(Player player, String tagId) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty()) return;
        if (opt.get().getStatus() != TagStatus.APPROVED) {
            msg(player, cfg.msg("tag-select-not-approved"));
            return;
        }
        // While random rotation is live, ChatTagListener#resolveTagText ignores activeTagId
        // completely and picks a fresh tag from the pool for every message - so letting this go
        // through would flip data.activeTagId, tell the player "Tag equipped", and then silently do
        // nothing in chat. Block it here with an explicit, honest reason instead of a fake success.
        if (data.isRandomTagEnabled() && data.approvedTagCount() >= cfg.randomMinTags()) {
            msg(player, cfg.msg("tag-select-random-active"));
            return;
        }
        data.setActiveTagId(tagId);
        plugin.data().save(data);
        msg(player, cfg.msg("tag-selected"));
    }

    /**
     * Mirrors {@link #selectTag}: unequips a tag rather than equipping one. Only clears
     * activeTagId when it's actually the one currently equipped - a stale/late click (e.g. the
     * player equipped a different tag from another session/window first) must not blow away
     * whatever is genuinely active right now.
     */
    public void unselectTag(Player player, String tagId) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        if (!tagId.equals(data.getActiveTagId())) return;
        data.setActiveTagId(null);
        plugin.data().save(data);
        msg(player, cfg.msg("tag-unselected"));
    }

    /**
     * Keeps random-tag state honest whenever a tag stops existing or stops being APPROVED (deleted,
     * silently removed, or rejected after being approved before): drops it from the player's manual
     * rotation subset, and formally turns random mode back off (instead of just letting it silently
     * "not apply" in {@link com.mellishy.customtag.listener.ChatTagListener}) once they fall below
     * {@code random-tag.min-tags}. This keeps the saved flag and the GUI/placeholder-visible state in
     * sync - random mode is disabled ONE authoritative way, not "still on but ignored everywhere".
     */
    private void syncRandomStateAfterRemoval(PlayerData data, String removedTagId) {
        data.getRandomTagPool().remove(removedTagId);
        if (data.isRandomTagEnabled() && data.approvedTagCount() < plugin.config().randomMinTags()) {
            data.setRandomTagEnabled(false);
        }
    }

    public void deleteTag(Player player, String tagId) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty()) return;
        TagEntry tag = opt.get();

        boolean wasPending = tag.getStatus() == TagStatus.PENDING;
        boolean wasRejected = tag.getStatus() == TagStatus.REJECTED;
        data.getTags().remove(tag);
        // Only live slots (PENDING/APPROVED) still hold a consumed token. A REJECTED entry's
        // token was already settled by the rejection's own refund policy (tokens/settings.yml) -
        // refunding again here would let players farm tokens by deleting their rejection history.
        if (!wasRejected) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1,
                    wasPending ? "request-cancelled" : "tag-deleted", player.getName());
        }
        if (tagId.equals(data.getActiveTagId())) data.setActiveTagId(null);
        syncRandomStateAfterRemoval(data, tagId);

        if (wasPending) {
            // withdraw the queue record too, so staff never review a request whose tag is gone
            platform().requests().byTag(player.getUniqueId(), tagId).ifPresent(request -> {
                platform().requests().cancel(request.getRequestId(), player.getName());
                platform().audit().log(AuditCategory.REQUEST, "cancelled", player.getName(),
                        player.getName(), request.getPlayerCustomId(), request.getRequestId(), null);
                platform().webhooks().publish(WebhookEventType.REQUEST_CANCELLED, platform().requestPayload(request));
            });
            plugin.cooldown().apply(data, cfg.cancelCooldownSeconds());
            plugin.data().save(data);
            msg(player, cfg.msg("request-cancelled"));
        } else {
            plugin.data().save(data);
            msg(player, cfg.msg("tag-deleted"));
        }
    }

    public boolean canOpenCreateMethod(PlayerData data) {
        if (data.isReservationActive()) return true; // resuming an in-progress reservation
        // Must mirror reserveForCreation's checks exactly (including activeTagCount(), not
        // getTags().size() - see PlayerData#activeTagCount) - this is only ever used to decide
        // whether to show the create-method menu at all, so if it ever drifted out of sync with
        // the real reservation check, a player could see "Create" as available, click it, and get
        // silently bounced back out by reserveForCreation for a reason the menu never warned them about.
        return data.getTokens() > 0
                && !data.hasPending()
                && data.activeTagCount() < effectiveMaxTags(data)
                && !plugin.cooldown().isOnCooldown(data)
                && !platform().tokens().isFrozen(data.getUuid())
                && !platform().security().isUnderMaintenance("submissions");
    }

    // ---------- admin actions ----------

    /**
     * Admin actions can target a player who is offline and - on servers with cache-eviction
     * enabled - possibly no longer in the in-memory cache at all (see DataManager#scheduleEviction).
     * {@link com.mellishy.customtag.data.DataManager#get} alone would silently hand back a blank,
     * freshly-created PlayerData in that case instead of their real one. This makes sure the real
     * data is loaded first. Cheap/no-op on the vast majority of servers where eviction is disabled
     * or the target simply hasn't been evicted.
     */
    public PlayerData loadTarget(UUID targetUuid) {
        String name = offlineName(targetUuid);
        plugin.data().ensureLoaded(targetUuid, name);
        return plugin.data().get(targetUuid, name);
    }

    /**
     * {@link #loadTarget} without the main-thread stall. The {@code ensureLoaded} inside it is a
     * real JDBC/Mongo round trip whenever the target isn't cached, which is precisely the case for
     * the offline players admin menus are opened on once cache-eviction is enabled - so the read is
     * moved off the main thread and {@code afterOnMain} runs once the data is in the cache, with
     * {@link #loadTarget} then guaranteed to be a pure map lookup.
     *
     * Runs the callback inline when the player is already cached, so nothing gains a tick of delay
     * on the common path.
     */
    public void loadTargetAsync(UUID targetUuid, Runnable afterOnMain) {
        plugin.data().ensureLoadedAsync(targetUuid, offlineName(targetUuid), afterOnMain);
    }

    /**
     * Staff review lock check shared by every staff decision path: when another staff member
     * holds a live lock on this tag's request, the action is refused and the acting admin told
     * who has it - no two staff may ever act on the same request at once.
     */
    private boolean blockedByReviewLock(Player admin, UUID targetUuid, String tagId) {
        Optional<TagRequest> request = platform().requests().byTag(targetUuid, tagId);
        if (request.isPresent()
                && platform().requests().isLockedByOther(request.get().getRequestId(), admin.getUniqueId())) {
            String holder = request.get().getLockedByName() == null ? "another staff member" : request.get().getLockedByName();
            msg(admin, plugin.config().msg("request-locked").replace("{staff}", holder));
            return true;
        }
        return false;
    }

    public void approve(Player admin, UUID targetUuid, String tagId) {
        if (blockedByReviewLock(admin, targetUuid, tagId)) return;
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;

        // close the queue record first - decide() is the duplicate-approval guard (empty result =
        // someone/something else already decided while this admin had the GUI open)
        Optional<TagRequest> request = platform().requests().byTag(targetUuid, tagId);
        if (request.isPresent()) {
            Optional<TagRequest> decided = platform().requests().decide(request.get().getRequestId(),
                    RequestStatus.APPROVED, DecisionActor.STAFF, admin.getName(), null, false);
            if (decided.isEmpty()) {
                msg(admin, plugin.config().msg("request-already-decided"));
                return;
            }
            finalizeApproval(data, opt.get(), decided.get(), DecisionActor.STAFF, admin.getName());
        } else {
            // legacy pending tag from before the queue system existed - approve it directly
            finalizeApproval(data, opt.get(), null, DecisionActor.STAFF, admin.getName());
        }
    }

    /** AI (or API) approval path - the request copy is already decided-checked by the caller. */
    private void approveByRequest(TagRequest request, DecisionActor actor, String actorName) {
        PlayerData data = loadTarget(request.getPlayerUuid());
        Optional<TagEntry> opt = data.getTagById(request.getTagId());
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) {
            // the tag disappeared (player deleted it in the same tick, data wipe, ...) - close the
            // request so it can't sit in the queue pointing at nothing
            platform().requests().decide(request.getRequestId(), RequestStatus.REMOVED,
                    actor, actorName, "tag no longer exists", false);
            return;
        }
        Optional<TagRequest> decided = platform().requests().decide(request.getRequestId(),
                RequestStatus.APPROVED, actor, actorName, null, false);
        if (decided.isEmpty()) return;
        finalizeApproval(data, opt.get(), decided.get(), actor, actorName);
    }

    /** The single place a tag actually goes live, shared by staff, AI and legacy approvals. */
    private void finalizeApproval(PlayerData data, TagEntry tag, TagRequest request,
                                  DecisionActor actor, String actorName) {
        tag.setStatus(TagStatus.APPROVED);
        tag.setRejectReason(null);
        // a freshly approved tag automatically takes priority and becomes the active one
        data.setActiveTagId(tag.getId());
        plugin.data().save(data);

        String requestId = request == null ? null : request.getRequestId();
        platform().audit().log(AuditCategory.REQUEST, "approved", actorName, data.getLastKnownName(),
                platform().playerIds().display(data.getUuid()), requestId, ColorUtil.stripToPlain(tag.getRawText()));
        Bukkit.getPluginManager().callEvent(new TagRequestApprovedEvent(
                data.getUuid(), requestId, tag.getRawText(), actor, actorName));
        if (request != null) {
            platform().webhooks().publish(WebhookEventType.REQUEST_APPROVED, platform().requestPayload(request));
        }
        platform().notifyPlayerDataChanged(data.getUuid());

        String text = plugin.config().rawMsg("request-approved-dm");
        notify(data.getUuid(), text, false, "approve", "entity.player.levelup");
    }

    @EventHandler
    public void onAdminReject(AdminRejectEvent event) {
        String[] parts = event.getTarget().split(":", 2);
        if (parts.length != 2) return;
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException ex) {
            // the target string is assembled by the plugin itself, so this only happens if something
            // upstream is broken - swallow it rather than letting it escape the event handler and
            // abort every other listener on this event
            plugin.getLogger().warning("Ignoring admin rejection with an unreadable target: " + event.getTarget());
            return;
        }
        reject(event.getAdmin(), targetUuid, parts[1], event.getReason());
    }

    /**
     * Rejection reasons end up in item lore, chat, webhooks, the audit log and the player's stored
     * tag history - all of which are replayed long after the fact. Nothing upstream bounds them: the
     * chat-input path is limited only by whatever the client will send, and {@code CustomTagAPI}
     * lets an integration pass a string of any size at all. An unbounded reason serialized into a
     * lore line is an oversized-packet kick waiting to happen for whoever opens that menu.
     */
    private static final int MAX_REJECT_REASON_LENGTH = 256;

    private static String cappedReason(String reason) {
        if (reason == null || reason.length() <= MAX_REJECT_REASON_LENGTH) return reason;
        return reason.substring(0, MAX_REJECT_REASON_LENGTH - 3) + "...";
    }

    public void reject(Player admin, UUID targetUuid, String tagId, String rawReason) {
        String reason = cappedReason(rawReason);
        if (blockedByReviewLock(admin, targetUuid, tagId)) return;
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;

        boolean refund = platform().refundOnStaffReject();
        Optional<TagRequest> request = platform().requests().byTag(targetUuid, tagId);
        TagRequest decidedCopy = null;
        if (request.isPresent()) {
            Optional<TagRequest> decided = platform().requests().decide(request.get().getRequestId(),
                    RequestStatus.REJECTED, DecisionActor.STAFF, admin.getName(), reason, refund);
            if (decided.isEmpty()) {
                msg(admin, plugin.config().msg("request-already-decided"));
                return;
            }
            decidedCopy = decided.get();
        }
        finalizeRejection(data, opt.get(), decidedCopy, DecisionActor.STAFF, admin.getName(), reason, refund);
    }

    /** AI rejection path - mirrors {@link #approveByRequest}. */
    private void rejectByRequest(TagRequest request, DecisionActor actor, String actorName,
                                 String rawReason, boolean refund) {
        String reason = cappedReason(rawReason);
        PlayerData data = loadTarget(request.getPlayerUuid());
        Optional<TagEntry> opt = data.getTagById(request.getTagId());
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) {
            platform().requests().decide(request.getRequestId(), RequestStatus.REMOVED,
                    actor, actorName, "tag no longer exists", false);
            return;
        }
        Optional<TagRequest> decided = platform().requests().decide(request.getRequestId(),
                RequestStatus.REJECTED, actor, actorName, reason, refund);
        if (decided.isEmpty()) return;
        finalizeRejection(data, opt.get(), decided.get(), actor, actorName, reason, refund);
    }

    /** The single place a pending tag becomes REJECTED, shared by staff and AI rejections. */
    private void finalizeRejection(PlayerData data, TagEntry tag, TagRequest request,
                                   DecisionActor actor, String actorName, String reason, boolean refund) {
        tag.setStatus(TagStatus.REJECTED);
        tag.setRejectReason(reason);
        syncRandomStateAfterRemoval(data, tag.getId());
        // Caps how many REJECTED tags stay in this player's history (tokens.max-rejected-history) -
        // see PlayerData#pruneRejectedHistory for the full reasoning. Pure data-only logic lives
        // there (unit-tested) rather than here, so TagService just wires the config value in.
        data.pruneRejectedHistory(plugin.config().maxRejectedHistory());
        if (refund) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1,
                    request == null ? "staff-reject" : request.getRequestId(), actorName);
        }
        plugin.data().save(data);

        String requestId = request == null ? null : request.getRequestId();
        platform().audit().log(AuditCategory.REQUEST, "rejected", actorName, data.getLastKnownName(),
                platform().playerIds().display(data.getUuid()), requestId,
                "reason=" + reason + " refund=" + refund);
        Bukkit.getPluginManager().callEvent(new TagRequestRejectedEvent(
                data.getUuid(), requestId, tag.getRawText(), reason, refund, actor, actorName));
        if (request != null) {
            platform().webhooks().publish(WebhookEventType.REQUEST_REJECTED, platform().requestPayload(request));
        }
        platform().notifyPlayerDataChanged(data.getUuid());

        String text = plugin.config().rawMsg("request-rejected-dm").replace("{reason}", reason);
        notify(data.getUuid(), text, false, "reject", "entity.villager.no");
    }

    /**
     * Silent moderation action in the admin queue: removes the player's pending request entirely -
     * no chat message is ever sent to the player, and it will not show up in their tag list
     * afterward (unlike a normal reject, which keeps a visible "Rejected" entry with a reason).
     *
     * {@code refundToken} is an explicit, per-click choice made by the admin (two distinct
     * buttons/lore lines in the queue - see GuiListener#handleAdminList): SHIFT+Right-click
     * refunds the token as a courtesy, Drop (Q) removes it for good with no refund, as a real
     * penalty. This lets an admin pick per-case instead of every silent removal on the server
     * behaving identically.
     */
    public void rejectSilent(Player admin, UUID targetUuid, String tagId, boolean refundToken) {
        if (blockedByReviewLock(admin, targetUuid, tagId)) return;
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;

        data.getTags().remove(opt.get());
        if (tagId.equals(data.getActiveTagId())) data.setActiveTagId(null);
        syncRandomStateAfterRemoval(data, tagId);
        if (refundToken) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1, "silent-remove", admin.getName());
        }
        plugin.data().save(data);
        platform().requests().byTag(targetUuid, tagId).ifPresent(request ->
                platform().requests().remove(request.getRequestId(), DecisionActor.STAFF, admin.getName(), refundToken));
        platform().audit().log(AuditCategory.STAFF, "silent-remove", admin.getName(), data.getLastKnownName(),
                platform().playerIds().display(targetUuid), null, "refund=" + refundToken);
        platform().notifyPlayerDataChanged(targetUuid);
        // deliberately no notify() call here - that's the whole point of "silent"
    }

    /**
     * Silent moderation action for a tag that already has a status (approved or previously
     * rejected), not just a fresh pending one - e.g. an admin quietly cleaning up an inappropriate
     * tag without alerting the player. No chat message is ever sent. See {@link #rejectSilent} for
     * why the refund is an explicit parameter now.
     */
    public void deleteSilent(Player admin, UUID targetUuid, String tagId, boolean refundToken) {
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty()) return;

        data.getTags().remove(opt.get());
        if (tagId.equals(data.getActiveTagId())) data.setActiveTagId(null);
        syncRandomStateAfterRemoval(data, tagId);
        if (refundToken) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1, "silent-delete", admin.getName());
        }
        plugin.data().save(data);
        platform().requests().byTag(targetUuid, tagId).ifPresent(request ->
                platform().requests().remove(request.getRequestId(), DecisionActor.STAFF, admin.getName(), refundToken));
        platform().audit().log(AuditCategory.STAFF, "silent-delete", admin.getName(), data.getLastKnownName(),
                platform().playerIds().display(targetUuid), null, "refund=" + refundToken);
        platform().notifyPlayerDataChanged(targetUuid);
    }

    // ---------- queue lifecycle callbacks ----------

    /**
     * Called from the platform's expiry sweep for every request that sat in the queue past
     * queue.expire-after-days: the tag becomes a visible REJECTED entry with an "expired"
     * reason, the token is refunded per policy, and the player is told (now or on next login).
     */
    public void handleExpiredRequest(TagRequest request) {
        PlayerData data = loadTarget(request.getPlayerUuid());
        data.getTagById(request.getTagId()).ifPresent(tag -> {
            if (tag.getStatus() == TagStatus.PENDING) {
                tag.setStatus(TagStatus.REJECTED);
                tag.setRejectReason(plugin.config().rawMsg("request-expired-reason"));
                syncRandomStateAfterRemoval(data, tag.getId());
                data.pruneRejectedHistory(plugin.config().maxRejectedHistory());
            }
        });
        if (platform().refundOnQueueExpired()) {
            platform().applyTokens(data, TokenTransactionType.REFUND, 1, request.getRequestId(), "expiry");
        }
        plugin.data().save(data);

        platform().audit().log(AuditCategory.REQUEST, "expired", "system", request.getPlayerName(),
                request.getPlayerCustomId(), request.getRequestId(), null);
        platform().webhooks().publish(WebhookEventType.REQUEST_EXPIRED, platform().requestPayload(request));
        platform().notifyPlayerDataChanged(request.getPlayerUuid());
        notify(request.getPlayerUuid(), plugin.config().rawMsg("request-expired-dm"), false, null, null);
    }

    /**
     * The undo path - no moderation decision should ever be irreversible. Reopens a CLOSED
     * request by id, restoring the player's tag entry to PENDING (recreating it when a silent
     * removal deleted it). Token balances are deliberately NOT touched here - reversing money is
     * a separate, explicit staff action (/customtag give/take) so an undo can never silently
     * double-refund; the audit entry records whether a refund had been paid out.
     *
     * @return true when the request was found and reopened.
     */
    public boolean undoRequest(CommandSender staff, String requestId) {
        Optional<TagRequest> before = platform().requests().byId(requestId);
        boolean hadRefund = before.map(TagRequest::isRefunded).orElse(false);
        DecisionActor actor = staff instanceof Player ? DecisionActor.STAFF : DecisionActor.CONSOLE;
        Optional<TagRequest> reopened = platform().requests().reopen(requestId, actor, staff.getName());
        if (reopened.isEmpty()) return false;
        TagRequest request = reopened.get();

        PlayerData data = loadTarget(request.getPlayerUuid());
        TagEntry tag = data.getTagById(request.getTagId()).orElseGet(() -> {
            TagEntry recreated = new TagEntry(request.getTagId(), request.getPlayerUuid(),
                    request.getRawText(), TagStatus.PENDING, request.getCreatedAt());
            data.getTags().add(recreated);
            return recreated;
        });
        if (tag.getId().equals(data.getActiveTagId())) data.setActiveTagId(null);
        tag.setStatus(TagStatus.PENDING);
        tag.setRejectReason(null);
        plugin.data().save(data);

        platform().audit().log(AuditCategory.STAFF, "undo", staff.getName(), request.getPlayerName(),
                request.getPlayerCustomId(), requestId,
                "request reopened" + (hadRefund ? " (a refund HAD been paid out - settle manually if needed)" : ""));
        platform().webhooks().publish(WebhookEventType.REQUEST_REOPENED, platform().requestPayload(request));
        platform().notifyPlayerDataChanged(request.getPlayerUuid());
        return true;
    }

    // ---------- decisions by request id (public API / future Discord-bot bridge) ----------

    /**
     * Approves an open request by its {@code REQ-XXXXXXXX} id, running the exact same pipeline
     * as a staff GUI approval (queue close, save, audit, Bukkit event, webhook, cross-server
     * sync, player DM). Main thread only.
     *
     * @return true when the request existed, was still open, and has now been approved.
     */
    public boolean approveRequestById(String requestId, DecisionActor actor, String actorName) {
        Optional<TagRequest> request = platform().requests().byId(requestId);
        if (request.isEmpty() || !request.get().getStatus().isOpen()) return false;
        approveByRequest(request.get(), actor, actorName);
        return true;
    }

    /**
     * Rejects an open request by its {@code REQ-XXXXXXXX} id - the by-id counterpart of
     * {@link #reject}. Main thread only.
     *
     * @return true when the request existed, was still open, and has now been rejected.
     */
    public boolean rejectRequestById(String requestId, DecisionActor actor, String actorName,
                                     String reason, boolean refund) {
        Optional<TagRequest> request = platform().requests().byId(requestId);
        if (request.isEmpty() || !request.get().getStatus().isOpen()) return false;
        rejectByRequest(request.get(), actor, actorName, reason, refund);
        return true;
    }

    // ---------- notifications ----------

    private String offlineName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString();
    }

    private void notify(UUID targetUuid, String legacyMessage, boolean resumeClickable,
                        String soundKey, String defaultSound) {
        Player online = Bukkit.getPlayer(targetUuid);
        String prefixed = plugin.config().raw().getString("messages.prefix", "") + legacyMessage;
        if (online != null && online.isOnline()) {
            online.sendMessage(ColorUtil.parse(prefixed));
            if (soundKey != null) {
                playSound(online, soundKey, defaultSound);
            }
        } else {
            PlayerData data = loadTarget(targetUuid);
            data.setPendingNotice(prefixed);
            data.setPendingNoticeResume(resumeClickable);
            plugin.data().save(data);
        }
    }

    /**
     * Plays one of the configurable UX feedback sounds (ux.sounds.* in config.yml). Sound names
     * are namespaced keys ("entity.player.levelup"); a blank value disables that sound. Uses the
     * string overload so any sound - including resource-pack customs - works without an enum.
     */
    private void playSound(Player player, String key, String def) {
        String sound = plugin.config().raw().getString("ux.sounds." + key, def);
        if (sound == null || sound.isBlank()) return;
        try {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Exception ignored) {
            // an invalid sound key must never break an approval/rejection flow
        }
    }

    /** Builds the clickable "you left mid-creation, click to resume" chat component shown on rejoin. */
    public Component buildResumeMessage(String legacyText) {
        ConfigManager cfg = plugin.config();
        return ColorUtil.parse(legacyText)
                .clickEvent(ClickEvent.runCommand("/customtag createnow"))
                .hoverEvent(HoverEvent.showText(ColorUtil.parse(cfg.rawMsg("resume-hover"))));
    }

    private void msg(Player player, String legacy) {
        player.sendMessage(ColorUtil.parse(legacy));
    }
}
