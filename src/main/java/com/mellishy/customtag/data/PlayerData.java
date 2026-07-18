package com.mellishy.customtag.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private String lastKnownName;
    private int tokens;
    private String activeTagId;
    private long cooldownUntil;
    private final List<TagEntry> tags = new ArrayList<>();
    /** A chat message queued to be delivered next time this player logs in (used when admin acts while they're offline). */
    private String pendingNotice;
    /** If true, the pendingNotice above should be sent as a clickable "resume creation" message instead of plain text. */
    private boolean pendingNoticeResume;

    /** True while a single token is reserved for an in-progress NEW tag creation (book handed out or chat awaiting input). */
    private boolean reservationActive;
    /** Unique id stamped on the creation book so an abandoned/refunded book can never be signed for a free tag later. */
    private String reservationId;

    /** True if this player wants a random one of their eligible tags shown per chat message instead of a single active tag. */
    private boolean randomTagEnabled;
    /**
     * Which approved tag-ids are allowed to be picked when random mode is on. An EMPTY list means
     * "use every approved tag" (the default, simplest behaviour) - a non-empty list is a deliberate
     * subset the player picked in the Random Tag Settings menu.
     */
    private final List<String> randomTagPool = new ArrayList<>();

    public PlayerData(UUID uuid, String lastKnownName, int tokens) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.tokens = tokens;
    }

    /**
     * Deep-copy constructor backing {@link #snapshot()}. Every mutable field (including both lists,
     * and every {@link TagEntry} they hold) is copied rather than shared, so the result is fully
     * independent of {@code other} - mutating one afterwards can never affect, or be affected by,
     * the other.
     */
    private PlayerData(PlayerData other) {
        this.uuid = other.uuid;
        this.lastKnownName = other.lastKnownName;
        this.tokens = other.tokens;
        this.activeTagId = other.activeTagId;
        this.cooldownUntil = other.cooldownUntil;
        for (TagEntry t : other.tags) {
            this.tags.add(t.copy());
        }
        this.pendingNotice = other.pendingNotice;
        this.pendingNoticeResume = other.pendingNoticeResume;
        this.reservationActive = other.reservationActive;
        this.reservationId = other.reservationId;
        this.randomTagEnabled = other.randomTagEnabled;
        this.randomTagPool.addAll(other.randomTagPool);
    }

    /**
     * Returns a fully independent deep copy of this object, safe to read from another thread (e.g.
     * a storage-backend I/O thread) even while the live instance kept in {@code DataManager}'s cache
     * keeps being mutated on the main thread. {@link PlayerData} and {@link TagEntry} are plain,
     * unsynchronized mutable objects by design (every field is touched constantly from GUI clicks,
     * commands, and events, all on the main thread) - the only place that's ever a problem is when a
     * reference escapes to a different thread, which is exactly what
     * {@link com.mellishy.customtag.data.DataManager#save} guards against by snapshotting here
     * before handing off to the I/O executor. Without this, an in-progress serialization
     * (e.g. iterating {@link #getTags()} to build JSON/YAML) racing against a concurrent
     * add()/remove() on the same list from the main thread could throw
     * ConcurrentModificationException or persist a torn, half-updated state.
     */
    public PlayerData snapshot() {
        return new PlayerData(this);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public void setLastKnownName(String lastKnownName) {
        this.lastKnownName = lastKnownName;
    }

    public int getTokens() {
        return tokens;
    }

    public void setTokens(int tokens) {
        this.tokens = Math.max(0, tokens);
    }

    public void addTokens(int amount) {
        setTokens(this.tokens + amount);
    }

    public String getActiveTagId() {
        return activeTagId;
    }

    public void setActiveTagId(String activeTagId) {
        this.activeTagId = activeTagId;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public List<TagEntry> getTags() {
        return tags;
    }

    public Optional<TagEntry> getPendingTag() {
        return tags.stream().filter(t -> t.getStatus() == TagStatus.PENDING).findFirst();
    }

    public Optional<TagEntry> getTagById(String id) {
        return tags.stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    public Optional<TagEntry> getActiveTag() {
        if (activeTagId == null) return Optional.empty();
        return getTagById(activeTagId).filter(t -> t.getStatus() == TagStatus.APPROVED);
    }

    public boolean hasPending() {
        return getPendingTag().isPresent();
    }

    public String getPendingNotice() {
        return pendingNotice;
    }

    public void setPendingNotice(String pendingNotice) {
        this.pendingNotice = pendingNotice;
    }

    public boolean isPendingNoticeResume() {
        return pendingNoticeResume;
    }

    public void setPendingNoticeResume(boolean pendingNoticeResume) {
        this.pendingNoticeResume = pendingNoticeResume;
    }

    public boolean isReservationActive() {
        return reservationActive;
    }

    public void setReservationActive(boolean reservationActive) {
        this.reservationActive = reservationActive;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public boolean isRandomTagEnabled() {
        return randomTagEnabled;
    }

    public void setRandomTagEnabled(boolean randomTagEnabled) {
        this.randomTagEnabled = randomTagEnabled;
    }

    public List<String> getRandomTagPool() {
        return randomTagPool;
    }

    /** How many APPROVED tags this player currently owns (the number that actually matters for the random feature). */
    public long approvedTagCount() {
        return tags.stream().filter(t -> t.getStatus() == TagStatus.APPROVED).count();
    }

    /**
     * How many tags currently count against {@code max-tags-per-player} in config.yml: every
     * PENDING or APPROVED tag, but deliberately NOT rejected ones. A rejected tag is kept in
     * {@link #getTags()} purely as visible history (so the player can see why it was rejected in
     * their tag list) - it isn't a "slot" the player is actually using, so it must never
     * permanently eat into their tag limit. Without this distinction, a player who accumulated a
     * few rejected attempts could get silently locked out of ever creating another tag until they
     * happened to notice and manually delete the old rejected entries, which was never the intent
     * of the limit (it exists to cap how many *live* tags one player can hold, not their lifetime
     * submission count).
     */
    public long activeTagCount() {
        return tags.stream().filter(t -> t.getStatus() != TagStatus.REJECTED).count();
    }

    /**
     * Resolves the effective pool of tags random mode may pick from right now: the player's chosen
     * subset intersected with tags that are STILL approved (a tag can be deleted/rejected after being
     * chosen), falling back to "every approved tag" when no subset was ever picked.
     */
    public List<TagEntry> resolveRandomPool() {
        List<TagEntry> approved = tags.stream().filter(t -> t.getStatus() == TagStatus.APPROVED).toList();
        if (randomTagPool.isEmpty()) {
            return approved;
        }
        List<TagEntry> filtered = approved.stream().filter(t -> randomTagPool.contains(t.getId())).toList();
        // the whole subset became invalid (all deleted/rejected since being picked) - fail safe to "every approved tag"
        return filtered.isEmpty() ? approved : filtered;
    }

    /**
     * Caps how many REJECTED tags stay in {@link #getTags()} (see tokens.max-rejected-history in
     * config.yml), called by {@link com.mellishy.customtag.service.TagService#reject} right after a
     * tag is rejected. REJECTED tags deliberately never count against max-tags-per-player (see
     * {@link #activeTagCount()}) specifically so a player can never be locked out by their own
     * rejection history - but with no cap at all, that history would grow without bound for the
     * lifetime of the account (every submit-and-get-rejected cycle adds one more permanent row to
     * this player's saved data, forever). Only the OLDEST rejected entries beyond {@code cap} are
     * dropped (oldest by {@link TagEntry#getUpdatedAt()}, i.e. the moment each was rejected), so the
     * most recent rejections - the ones actually still relevant to the player - are always the ones
     * kept visible in their tag list.
     *
     * @param cap maximum rejected entries to keep; {@code <= 0} disables pruning entirely (unbounded history).
     */
    public void pruneRejectedHistory(int cap) {
        if (cap <= 0) return;

        List<TagEntry> rejected = tags.stream()
                .filter(t -> t.getStatus() == TagStatus.REJECTED)
                .sorted(Comparator.comparingLong(TagEntry::getUpdatedAt))
                .toList();
        int excess = rejected.size() - cap;
        if (excess <= 0) return;
        tags.removeAll(rejected.subList(0, excess));
    }
}
