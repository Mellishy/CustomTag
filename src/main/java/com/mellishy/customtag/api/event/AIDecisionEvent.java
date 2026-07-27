package com.mellishy.customtag.api.event;

import com.mellishy.customtag.ai.AIDecision;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired on the main thread whenever the AI moderation system produced a decision for a request
 * (before the resulting approve/reject/escalate action is applied).
 */
public class AIDecisionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String requestId;
    private final AIDecision decision;

    public AIDecisionEvent(UUID playerUuid, String requestId, AIDecision decision) {
        this.playerUuid = playerUuid;
        this.requestId = requestId;
        this.decision = decision;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getRequestId() { return requestId; }
    public AIDecision getDecision() { return decision; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
