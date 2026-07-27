package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Pairs with {@link com.mellishy.customtag.data.DataManager#scheduleEviction} (see PlayerQuitListener) to make cache eviction
 * completely invisible to the player: {@link AsyncPlayerPreLoginEvent} is, by contract, always
 * fired on a background thread by the server well before the player actually joins - exactly the
 * right place to do a blocking-ish backend read without ever touching the main thread. By the time
 * {@link PlayerJoinListener} runs, the player's data is already back in the cache as if it had
 * never left.
 *
 * When storage.cache-eviction.enabled is false (the default), the cache never evicts anyone in the
 * first place, so {@code ensureLoaded} below is a single cheap map lookup and returns immediately -
 * this listener is always safe to have registered regardless of that setting.
 */
public class PlayerPreLoginListener implements Listener {

    private final MellishyCustomTag plugin;

    public PlayerPreLoginListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        plugin.data().ensureLoaded(event.getUniqueId(), event.getName());
    }
}