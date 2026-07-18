package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final MellishyCustomTag plugin;

    public PlayerJoinListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        data.setLastKnownName(player.getName());

        if (data.getPendingNotice() != null) {
            if (data.isPendingNoticeResume()) {
                // this is the "you left mid-creation, token refunded" notice - make it clickable
                // so the player can jump straight back into the creation menu
                Component component = plugin.tagService().buildResumeMessage(data.getPendingNotice());
                player.sendMessage(component);
            } else {
                player.sendMessage(ColorUtil.parse(data.getPendingNotice()));
            }
            data.setPendingNotice(null);
            data.setPendingNoticeResume(false);
        }
        plugin.data().save(data);
    }
}
