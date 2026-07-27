package com.mellishy.customtag.api.event;

import com.mellishy.customtag.request.DecisionActor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired on the main thread after a tag request was approved (by staff, AI or the API) and the
 * tag went live.
 */
public class TagRequestApprovedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String requestId;
    private final String rawText;
    private final DecisionActor actorType;
    private final String actorName;

    public TagRequestApprovedEvent(UUID playerUuid, String requestId, String rawText,
                                   DecisionActor actorType, String actorName) {
        this.playerUuid = playerUuid;
        this.requestId = requestId;
        this.rawText = rawText;
        this.actorType = actorType;
        this.actorName = actorName;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getRequestId() { return requestId; }
    public String getRawText() { return rawText; }
    /** Whether a human, the AI, the console or an external API approved it. */
    public DecisionActor getActorType() { return actorType; }
    public String getActorName() { return actorName; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
