package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * If a player disconnects while they had a token reserved for an in-progress tag creation
 * (book handed out or awaiting chat input), that reservation is refunded immediately and a
 * clickable "resume creation" message is queued for their next login. See TagService's class
 * javadoc for the full explanation of why this is dupe-proof.
 */
public class PlayerQuitListener implements Listener {

    private final MellishyCustomTag plugin;

    public PlayerQuitListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.tagService().handleDisconnect(event.getPlayer());
        // no-op unless storage.cache-eviction.enabled is true in config.yml - see DataManager#scheduleEviction
        plugin.data().scheduleEviction(event.getPlayer().getUniqueId());
    }
}