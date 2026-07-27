package com.mellishy.customtag.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One tag request travelling through the moderation pipeline. This is the queue-level record
 * (request id, status transitions, who decided, AI metadata, lock state) - the tag TEXT itself
 * still lives on the player's {@link com.mellishy.customtag.data.TagEntry}, linked by
 * {@link #getTagId()}, so there is exactly one source of truth for tag content and this class
 * never duplicates it beyond the immutable submission-time copy kept for history/webhooks.
 *
 * THREAD SAFETY: mutated only through {@link RequestManager}, which synchronizes on the request
 * instance for every transition. Reads from other threads (webhook worker building an embed)
 * only ever see a {@link #copy()} handed out by the manager.
 */
public class TagRequest {

    /** One entry of the request's audit history - every status change appends one of these. */
    public record Transition(long at, RequestStatus from, RequestStatus to, DecisionActor actor,
                             String actorName, String note) {}

    private final String requestId;
    private final UUID playerUuid;
    private final String playerName;
    private final String playerCustomId;
    private final String tagId;
    private final String rawText;
    private final String plainText;
    private final String serverName;
    private final long createdAt;
    private final int priority;

    private RequestStatus status;
    private long updatedAt;
    private String decidedByName;
    private DecisionActor decidedByActor;
    private String rejectReason;
    private boolean refunded;

    private String aiProvider;
    private String aiModel;
    private int aiConfidence = -1;
    private String aiReason;

    private UUID lockedBy;
    private String lockedByName;
    private long lockedAt;

    private final List<Transition> history = new ArrayList<>();

    public TagRequest(String requestId, UUID playerUuid, String playerName, String playerCustomId,
                      String tagId, String rawText, String plainText, String serverName,
                      int priority, long createdAt) {
        this.requestId = requestId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.playerCustomId = playerCustomId;
        this.tagId = tagId;
        this.rawText = rawText;
        this.plainText = plainText;
        this.serverName = serverName;
        this.priority = priority;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = RequestStatus.PENDING;
    }

    /** Deep-copy constructor backing {@link #copy()}. */
    private TagRequest(TagRequest other) {
        this.requestId = other.requestId;
        this.playerUuid = other.playerUuid;
        this.playerName = other.playerName;
        this.playerCustomId = other.playerCustomId;
        this.tagId = other.tagId;
        this.rawText = other.rawText;
        this.plainText = other.plainText;
        this.serverName = other.serverName;
        this.priority = other.priority;
        this.createdAt = other.createdAt;
        this.status = other.status;
        this.updatedAt = other.updatedAt;
        this.decidedByName = other.decidedByName;
        this.decidedByActor = other.decidedByActor;
        this.rejectReason = other.rejectReason;
        this.refunded = other.refunded;
        this.aiProvider = other.aiProvider;
        this.aiModel = other.aiModel;
        this.aiConfidence = other.aiConfidence;
        this.aiReason = other.aiReason;
        this.lockedBy = other.lockedBy;
        this.lockedByName = other.lockedByName;
        this.lockedAt = other.lockedAt;
        this.history.addAll(other.history); // Transition is an immutable record - sharing is safe
    }

    /** Fully independent snapshot safe to hand to another thread. */
    public TagRequest copy() {
        return new TagRequest(this);
    }

    // ---- package-private mutators: only RequestManager may transition a request ----

    void transition(RequestStatus to, DecisionActor actor, String actorName, String note, long now) {
        history.add(new Transition(now, this.status, to, actor, actorName, note));
        this.status = to;
        this.updatedAt = now;
        if (to.isDecided() || to == RequestStatus.REMOVED) {
            this.decidedByActor = actor;
            this.decidedByName = actorName;
        }
    }

    void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    void setRefunded(boolean refunded) { this.refunded = refunded; }

    void setAiResult(String provider, String model, int confidence, String reason) {
        this.aiProvider = provider;
        this.aiModel = model;
        this.aiConfidence = confidence;
        this.aiReason = reason;
    }

    void lock(UUID staff, String staffName, long now) {
        this.lockedBy = staff;
        this.lockedByName = staffName;
        this.lockedAt = now;
    }

    void unlock() {
        this.lockedBy = null;
        this.lockedByName = null;
        this.lockedAt = 0;
    }

    // ---- getters ----

    public String getRequestId() { return requestId; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public String getPlayerCustomId() { return playerCustomId; }
    public String getTagId() { return tagId; }
    public String getRawText() { return rawText; }
    public String getPlainText() { return plainText; }
    public String getServerName() { return serverName; }
    public long getCreatedAt() { return createdAt; }
    public int getPriority() { return priority; }
    public RequestStatus getStatus() { return status; }
    public long getUpdatedAt() { return updatedAt; }
    public String getDecidedByName() { return decidedByName; }
    public DecisionActor getDecidedByActor() { return decidedByActor; }
    public String getRejectReason() { return rejectReason; }
    public boolean isRefunded() { return refunded; }
    public String getAiProvider() { return aiProvider; }
    public String getAiModel() { return aiModel; }
    public int getAiConfidence() { return aiConfidence; }
    public String getAiReason() { return aiReason; }
    public UUID getLockedBy() { return lockedBy; }
    public String getLockedByName() { return lockedByName; }
    public long getLockedAt() { return lockedAt; }
    public List<Transition> getHistory() { return List.copyOf(history); }
}
