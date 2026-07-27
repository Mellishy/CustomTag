package com.mellishy.customtag.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired on the main thread right after a tag request passed validation and entered the global
 * pending queue. Read-only - external plugins observe; decisions still flow through the queue.
 */
public class TagRequestCreatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String playerName;
    private final String requestId;
    private final String rawText;

    public TagRequestCreatedEvent(UUID playerUuid, String playerName, String requestId, String rawText) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.requestId = requestId;
        this.rawText = rawText;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    /** The queue id, e.g. {@code REQ-00000042}. */
    public String getRequestId() { return requestId; }
    /** Exactly what the player submitted, color codes included. */
    public String getRawText() { return rawText; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
