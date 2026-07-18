package com.mellishy.customtag.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired whenever a player finishes submitting tag text, regardless of whether they
 * used the chat method or the book method. editingTagId is null for brand-new tags.
 */
public class TagSubmitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String editingTagId;
    private final String rawText;
    private final String reservationId;

    public TagSubmitEvent(Player player, String editingTagId, String rawText, String reservationId) {
        super(); // always dispatched on the main thread
        this.player = player;
        this.editingTagId = editingTagId;
        this.rawText = rawText;
        this.reservationId = reservationId;
    }

    public Player getPlayer() {
        return player;
    }

    public String getEditingTagId() {
        return editingTagId;
    }

    public String getRawText() {
        return rawText;
    }

    /** Only set (non-null) for brand-new tag submissions; null for edits, which never consume a token. */
    public String getReservationId() {
        return reservationId;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
