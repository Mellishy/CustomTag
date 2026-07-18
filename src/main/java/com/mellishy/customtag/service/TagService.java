package com.mellishy.customtag.service;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.event.AdminRejectEvent;
import com.mellishy.customtag.event.TagSubmitEvent;
import com.mellishy.customtag.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Optional;
import java.util.UUID;

/**
 * Central place for every rule described in the spec: single-pending-request lock,
 * token economy (now reservation-based so tokens can never be duplicated or lost through
 * a disconnect/death mid-creation), cooldowns, auto-priority of newly approved tags,
 * refunds on deletion, etc. GUIs, listeners and commands call the public methods here
 * instead of touching PlayerData directly, so the rules only ever live in one place.
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

    // ---------- submission (chat or book, new or edit) ----------

    @EventHandler
    public void onSubmit(TagSubmitEvent event) {
        Player player = event.getPlayer();
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        if (event.getEditingTagId() == null) {
            submitNew(player, data, cfg, event.getRawText(), event.getReservationId());
        } else {
            submitEdit(player, data, cfg, event.getEditingTagId(), event.getRawText());
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

        TagEntry entry = new TagEntry(UUID.randomUUID().toString(), player.getUniqueId(), rawText, TagStatus.PENDING, System.currentTimeMillis());
        data.getTags().add(entry);
        // the token backing this tag was already deducted when the reservation was made - just clear it now
        data.setReservationActive(false);
        data.setReservationId(null);
        plugin.data().save(data);
        msg(player, cfg.msg("request-submitted"));
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

        tag.setRawText(rawText);
        tag.setStatus(TagStatus.PENDING);
        tag.setRejectReason(null);
        plugin.data().save(data);
        msg(player, cfg.msg("edit-submitted"));
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
        if (data.activeTagCount() >= cfg.maxTagsPerPlayer()) {
            msg(player, cfg.msg("max-tags-reached").replace("{max}", String.valueOf(cfg.maxTagsPerPlayer())));
            return null;
        }

        data.addTokens(-1);
        String id = UUID.randomUUID().toString();
        data.setReservationActive(true);
        data.setReservationId(id);
        plugin.data().save(data);
        return id;
    }

    /** Explicit, player-initiated cancel (Back button, typing "cancel"). Refunds if a reservation is active. No-op otherwise. */
    public void cancelReservation(Player player) {
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        if (releaseReservation(data)) {
            plugin.data().save(data);
        }
    }

    /** @return true if a reservation was actually active and has now been refunded+cleared. */
    private boolean releaseReservation(PlayerData data) {
        if (data.isReservationActive()) {
            data.addTokens(1);
            data.setReservationActive(false);
            data.setReservationId(null);
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
        if (releaseReservation(data)) {
            data.setPendingNotice(plugin.config().rawMsg("left-mid-creation"));
            data.setPendingNoticeResume(true);
        }
        plugin.data().save(data);
    }

    /**
     * Called from {@link com.mellishy.customtag.listener.BookEditListener} when a creation book
     * was removed from a player's death drops (so it isn't lost to the ground for someone else
     * to pick up). Since the book itself is gone, refund the reservation immediately and tell
     * the still-online player they can start again.
     */
    public void handleBookLostToDeath(Player player) {
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        if (releaseReservation(data)) {
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
        data.getTags().remove(tag);
        data.addTokens(1);
        if (tagId.equals(data.getActiveTagId())) data.setActiveTagId(null);
        syncRandomStateAfterRemoval(data, tagId);

        if (wasPending) {
            plugin.cooldown().apply(data, cfg.cancelCooldownSeconds());
            plugin.data().save(data);
            msg(player, cfg.msg("request-cancelled"));
        } else {
            plugin.data().save(data);
            msg(player, cfg.msg("tag-deleted"));
        }
    }

    public boolean canOpenCreateMethod(PlayerData data) {
        ConfigManager cfg = plugin.config();
        if (data.isReservationActive()) return true; // resuming an in-progress reservation
        // Must mirror reserveForCreation's checks exactly (including activeTagCount(), not
        // getTags().size() - see PlayerData#activeTagCount) - this is only ever used to decide
        // whether to show the create-method menu at all, so if it ever drifted out of sync with
        // the real reservation check, a player could see "Create" as available, click it, and get
        // silently bounced back out by reserveForCreation for a reason the menu never warned them about.
        return data.getTokens() > 0
                && !data.hasPending()
                && data.activeTagCount() < cfg.maxTagsPerPlayer()
                && !plugin.cooldown().isOnCooldown(data);
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

    public void approve(Player admin, UUID targetUuid, String tagId) {
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;
        TagEntry tag = opt.get();
        tag.setStatus(TagStatus.APPROVED);
        tag.setRejectReason(null);
        // a freshly approved tag automatically takes priority and becomes the active one
        data.setActiveTagId(tag.getId());
        plugin.data().save(data);

        String text = plugin.config().rawMsg("request-approved-dm");
        notify(targetUuid, text, false);
    }

    @EventHandler
    public void onAdminReject(AdminRejectEvent event) {
        String[] parts = event.getTarget().split(":", 2);
        if (parts.length != 2) return;
        reject(event.getAdmin(), UUID.fromString(parts[0]), parts[1], event.getReason());
    }

    public void reject(Player admin, UUID targetUuid, String tagId, String reason) {
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;
        TagEntry tag = opt.get();
        tag.setStatus(TagStatus.REJECTED);
        tag.setRejectReason(reason);
        syncRandomStateAfterRemoval(data, tagId);
        plugin.data().save(data);

        String text = plugin.config().rawMsg("request-rejected-dm").replace("{reason}", reason);
        notify(targetUuid, text, false);
    }

    /**
     * Silent moderation action in the admin queue: removes the player's pending request entirely -
     * no chat message is ever sent to the player, and it will not show up in their tag list
     * afterward (unlike a normal reject, which keeps a visible "Rejected" entry with a reason).
     *
     * {@code refundToken} is now an explicit, per-click choice made by the admin (two distinct
     * buttons/lore lines in the queue - see GuiListener#handleAdminList) rather than a single
     * server-wide config toggle: SHIFT+Right-click refunds the token as a courtesy, Drop (Q)
     * removes it for good with no refund, as a real penalty. This lets an admin pick per-case
     * instead of every silent removal on the server behaving identically.
     */
    public void rejectSilent(Player admin, UUID targetUuid, String tagId, boolean refundToken) {
        PlayerData data = loadTarget(targetUuid);
        Optional<TagEntry> opt = data.getTagById(tagId);
        if (opt.isEmpty() || opt.get().getStatus() != TagStatus.PENDING) return;

        data.getTags().remove(opt.get());
        if (tagId.equals(data.getActiveTagId())) data.setActiveTagId(null);
        syncRandomStateAfterRemoval(data, tagId);
        if (refundToken) {
            data.addTokens(1);
        }
        plugin.data().save(data);
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
            data.addTokens(1);
        }
        plugin.data().save(data);
    }

    private String offlineName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString();
    }

    private void notify(UUID targetUuid, String legacyMessage, boolean resumeClickable) {
        Player online = Bukkit.getPlayer(targetUuid);
        String prefixed = plugin.config().raw().getString("messages.prefix", "") + legacyMessage;
        if (online != null && online.isOnline()) {
            online.sendMessage(ColorUtil.parse(prefixed));
        } else {
            PlayerData data = loadTarget(targetUuid);
            data.setPendingNotice(prefixed);
            data.setPendingNoticeResume(resumeClickable);
            plugin.data().save(data);
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