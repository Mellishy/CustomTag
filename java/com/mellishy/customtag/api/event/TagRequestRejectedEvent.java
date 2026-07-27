package com.mellishy.customtag.api.event;

import com.mellishy.customtag.request.DecisionActor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired on the main thread after a tag request was rejected (by staff, AI or validation). */
public class TagRequestRejectedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String requestId;
    private final String rawText;
    private final String reason;
    private final boolean refunded;
    private final DecisionActor actorType;
    private final String actorName;

    public TagRequestRejectedEvent(UUID playerUuid, String requestId, String rawText, String reason,
                                   boolean refunded, DecisionActor actorType, String actorName) {
        this.playerUuid = playerUuid;
        this.requestId = requestId;
        this.rawText = rawText;
        this.reason = reason;
        this.refunded = refunded;
        this.actorType = actorType;
        this.actorName = actorName;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    /** May be null when validation rejected the submission before it ever entered the queue. */
    public String getRequestId() { return requestId; }
    public String getRawText() { return rawText; }
    public String getReason() { return reason; }
    /** Whether the creation token was returned to the player. */
    public boolean isRefunded() { return refunded; }
    public DecisionActor getActorType() { return actorType; }
    public String getActorName() { return actorName; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
