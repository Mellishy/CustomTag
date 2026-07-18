package com.mellishy.customtag.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** target = "<playerUuid>:<tagId>" */
public class AdminRejectEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player admin;
    private final String target;
    private final String reason;

    public AdminRejectEvent(Player admin, String target, String reason) {
        super();
        this.admin = admin;
        this.target = target;
        this.reason = reason;
    }

    public Player getAdmin() {
        return admin;
    }

    public String getTarget() {
        return target;
    }

    public String getReason() {
        return reason;
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
