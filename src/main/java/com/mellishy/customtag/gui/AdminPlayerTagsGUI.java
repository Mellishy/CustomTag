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
 * Paginated like {@link TagListGUI}: with unlimited rejected history a single player can easily
 * outgrow one chest inventory, and tags past the grid used to be unreachable for silent delete.
 *
 * Same explicit two-action silent moderation as the pending queue (see AdminGUI's javadoc):
 *   - SHIFT+Right-click: silently removed, token IS refunded.
 *   - Drop (Q): silently removed, token is NOT refunded.
 */
public class AdminPlayerTagsGUI {

    private final MellishyCustomTag plugin;

    public AdminPlayerTagsGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player admin, UUID targetUuid) {
        open(admin, targetUuid, 0);
    }

    public void open(Player admin, UUID targetUuid, int page) {
        plugin.tagService().loadTargetAsync(targetUuid, () -> {
            if (admin.isOnline()) build(admin, targetUuid, page);
        });
    }

    private void build(Player admin, UUID targetUuid, int page) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.tagService().loadTarget(targetUuid);

        int size = cfg.guiSize("admin-player-tags");
        List<TagEntry> tags = List.copyOf(data.getTags());
        int perPage = GuiFrame.contentPerPage(size);
        int totalPages = Math.max(1, (int) Math.ceil(tags.size() / (double) perPage));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        String rawTitle = cfg.guiTitle("admin-player-tags").replace("{player}", data.getLastKnownName());
        if (rawTitle.length() > 48) rawTitle = rawTitle.substring(0, 48);
        String title = totalPages > 1
                ? rawTitle + " &7[" + (currentPage + 1) + "/" + totalPages + "]"
                : rawTitle;

        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.ADMIN_PLAYER_TAGS);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(title));
        holder.setInventory(inv);
        holder.putContext(-1, targetUuid.toString());
        holder.setPage(currentPage);
        holder.setTotalPages(totalPages);

        GuiFrame.border(inv, size, cfg);

        int from = currentPage * perPage;
        int to = Math.min(from + perPage, tags.size());
        List<TagEntry> pageSlice = tags.isEmpty() ? List.of() : tags.subList(from, to);

        GuiFrame.ContentGrid grid = GuiFrame.contentGrid(size);
        int slot = grid.startSlot();
        for (TagEntry tag : pageSlice) {
            if (grid.isPastEnd(slot)) break;
            slot = GuiFrame.skipBorderColumn(slot);

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

            String plainText = ColorUtil.stripToPlain(tag.getRawText());
            if (plainText.isBlank()) plainText = "(blank)";

            List<String> lore = new ArrayList<>();
            lore.add("&7Preview: &f" + plainText);
            lore.add("&7Status: " + statusColor + prettyStatus(tag.getStatus()));
            if (tag.getStatus() == TagStatus.REJECTED && tag.getRejectReason() != null) {
                lore.add("&7Reason: &f" + ColorUtil.capLoreText(tag.getRejectReason()));
            }
            lore.add("");
            lore.add(cfg.rawMsg("admin-request-actions-silent-refund"));
            lore.add(cfg.rawMsg("admin-request-actions-silent-no-refund"));

            inv.setItem(slot, new ItemBuilder(mat)
                    .name(statusColor + "&l" + prettyStatus(tag.getStatus()))
                    .lore(lore)
                    .build());
            holder.putContext(slot, tag.getId());
            slot++;
        }

        if (tags.isEmpty()) {
            int rows = size / 9;
            int centerSlot = (rows / 2) * 9 + 4;
            inv.setItem(centerSlot, new ItemBuilder(Material.GRAY_DYE)
                    .name("&7This player has no tags")
                    .build());
        }

        int backSlot = cfg.guiSlot("admin-player-tags", "back-slot");
        inv.setItem(backSlot, new ItemBuilder(Material.BARRIER).name("&c&lClose").build());

        GuiFrame.renderPageFooter(inv, cfg, "admin-player-tags", currentPage, totalPages);
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
