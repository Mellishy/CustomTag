package com.mellishy.customtag.gui;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin view of a SPECIFIC player's tags, of any status - not just the pending review queue that
 * {@link AdminGUI} shows. This is what {@link com.mellishy.customtag.service.TagService#deleteSilent}
 * is actually for: an admin quietly cleaning up a tag that's already APPROVED (or a stale REJECTED
 * entry) without alerting the player, e.g. something that slipped through review and got reported
 * later. Opened via {@code /customtag managetags <player>}.
 *
 * Same explicit two-action silent moderation as the pending queue (see AdminGUI's javadoc):
 *   - SHIFT+Right-click: silently removed, token IS refunded.
 *   - Drop (Q): silently removed, token is NOT refunded.
 * Never sends the target player any chat message either way - that's the point of "silent".
 */
public class AdminPlayerTagsGUI {

    private final MellishyCustomTag plugin;

    public AdminPlayerTagsGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player admin, UUID targetUuid) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.tagService().loadTarget(targetUuid);

        int size = cfg.guiSize("admin-player-tags");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.ADMIN_PLAYER_TAGS);
        String title = cfg.guiTitle("admin-player-tags").replace("{player}", data.getLastKnownName());
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(title));
        holder.setInventory(inv);
        // stash the target uuid on a slot no item ever occupies, so the listener can find it back
        holder.putContext(-1, targetUuid.toString());

        GuiFrame.border(inv, size, cfg);

        GuiFrame.ContentGrid grid = GuiFrame.contentGrid(size);
        int slot = grid.startSlot();
        for (TagEntry tag : data.getTags()) {
            if (grid.isPastEnd(slot)) break;
            if ((slot + 1) % 9 == 0) slot += 2;

            Material mat = switch (tag.getStatus()) {
                case APPROVED -> Material.NAME_TAG;
                case PENDING -> Material.PAPER;
                case REJECTED -> Material.BARRIER;
            };
            String statusColor = switch (tag.getStatus()) {
                case APPROVED -> "&a";
                case PENDING -> "&e";
                case REJECTED -> "&c";
            };

            // Plain, uncolored text only - never the tag live-rendered - exactly like AdminGUI's
            // pending queue already does, and for the same reason: a deliberately glitchy or
            // interactive (click/hover) submission must never make this admin menu itself look
            // broken or carry a clickable payload the admin didn't expect.
            String plainText = ColorUtil.stripToPlain(tag.getRawText());
            if (plainText.isBlank()) plainText = "(blank)";

            List<String> lore = new ArrayList<>();
            lore.add("&7Preview: &f" + plainText);
            lore.add("&7Status: " + statusColor + prettyStatus(tag.getStatus()));
            if (tag.getStatus() == TagStatus.REJECTED && tag.getRejectReason() != null) {
                lore.add("&7Reason: &f" + tag.getRejectReason());
            }
            lore.add("");
            lore.add(cfg.rawMsg("admin-request-actions-silent-refund"));
            lore.add(cfg.rawMsg("admin-request-actions-silent-no-refund"));

            ItemStack item = new ItemBuilder(mat)
                    .name(statusColor + "&l" + prettyStatus(tag.getStatus()))
                    .lore(lore)
                    .build();

            inv.setItem(slot, item);
            holder.putContext(slot, tag.getId());
            slot++;
        }

        if (data.getTags().isEmpty()) {
            int rows = size / 9;
            int centerSlot = (rows / 2) * 9 + 4;
            inv.setItem(centerSlot, new ItemBuilder(Material.GRAY_DYE)
                    .name("&7This player has no tags")
                    .build());
        }

        int backSlot = cfg.guiSlot("admin-player-tags", "back-slot");
        inv.setItem(backSlot, new ItemBuilder(Material.BARRIER).name("&c&lClose").build());

        GuiFrame.fillEmptyCheckered(inv, size, cfg);

        admin.openInventory(inv);
    }

    private String prettyStatus(TagStatus status) {
        return switch (status) {
            case APPROVED -> "Approved";
            case PENDING -> "Pending";
            case REJECTED -> "Rejected";
        };
    }
}