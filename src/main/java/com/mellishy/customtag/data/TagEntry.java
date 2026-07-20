package com.mellishy.customtag.data;

import java.util.UUID;

/**
 * Represents a single custom-tag request/entry owned by a player.
 * A player may hold several of these (approved history, rejected history)
 * but only ever ONE with {@link TagStatus#PENDING}.
 */
public class TagEntry {

    private final String id;
    private final UUID owner;
    private String rawText;
    private TagStatus status;
    private String rejectReason;
    private long createdAt;
    private long updatedAt;

    /** Creates a brand-new tag entry, e.g. from a fresh player submission - updatedAt starts equal to createdAt. */
    public TagEntry(String id, UUID owner, String rawText, TagStatus status, long createdAt) {
        this(id, owner, rawText, status, createdAt, createdAt);
    }

    /**
     * Reconstructs a tag entry with its real, previously-recorded updatedAt - used when loading
     * from a {@link com.mellishy.customtag.data.storage.StorageBackend} (see
     * {@link com.mellishy.customtag.data.storage.TagJson#deserializeTags}), so a tag's actual last-
     * modified time survives a save/load cycle instead of silently resetting to its creation time
     * on every server restart or GUI-triggered cache refresh.
     */
    public TagEntry(String id, UUID owner, String rawText, TagStatus status, long createdAt, long updatedAt) {
        this.id = id;
        this.owner = owner;
        this.rawText = rawText;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Deep-copy constructor. Used by {@link PlayerData#snapshot()} to freeze a fully independent
     * copy of this entry before it's handed to the async I/O thread - the live TagEntry instances
     * kept in the cache are plain mutable fields with no synchronization, so a background thread
     * must never read them directly while the main thread might still be calling setStatus()/
     * setRawText() on the very same object (e.g. an admin approving/rejecting a request).
     */
    private TagEntry(TagEntry other) {
        this.id = other.id;
        this.owner = other.owner;
        this.rawText = other.rawText;
        this.status = other.status;
        this.rejectReason = other.rejectReason;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    /** Returns an independent snapshot of this entry, safe to hand to another thread. */
    public TagEntry copy() {
        return new TagEntry(this);
    }

    public String getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
        touch();
    }

    public TagStatus getStatus() {
        return status;
    }

    public void setStatus(TagStatus status) {
        this.status = status;
        touch();
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
