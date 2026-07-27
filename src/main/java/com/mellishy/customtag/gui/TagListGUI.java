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

/**
 * Player's own tag inventory. Paginated the same way as {@link AdminGUI}: once a player has more
 * tags than fit in the content grid (common when {@code tokens.max-rejected-history} is raised or
 * set to unlimited), older tags used to vanish from the menu with no way to reach them. Pages keep
 * every tag selectable / editable / deletable.
 */
public class TagListGUI {

    private final MellishyCustomTag plugin;

    public TagListGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        int size = cfg.guiSize("tag-list");
        List<TagEntry> tags = List.copyOf(data.getTags());
        int perPage = GuiFrame.contentPerPage(size);
        int totalPages = Math.max(1, (int) Math.ceil(tags.size() / (double) perPage));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        String rawTitle = cfg.guiTitle("tag-list");
        if (rawTitle.length() > 48) rawTitle = rawTitle.substring(0, 48);
        String title = totalPages > 1
                ? rawTitle + " &7[" + (currentPage + 1) + "/" + totalPages + "]"
                : rawTitle;

        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.TAG_LIST);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(title));
        holder.setInventory(inv);
        holder.setPage(currentPage);
        holder.setTotalPages(totalPages);

        GuiFrame.border(inv, size, cfg);

        boolean randomActive = data.isRandomTagEnabled() && data.approvedTagCount() >= cfg.randomMinTags();
        List<TagEntry> randomPool = randomActive ? data.resolveRandomPool() : List.of();

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

            boolean active = !randomActive && tag.getId().equals(data.getActiveTagId());
            boolean inRandomPool = randomActive && randomPool.stream().anyMatch(t -> t.getId().equals(tag.getId()));

            List<String> lore = new ArrayList<>();
            // parseForOthers + toLegacyString: keeps cosmetics, strips interactive MiniMessage
            String safePreview = ColorUtil.toLegacyString(ColorUtil.parseForOthers(tag.getRawText()));
            lore.add("&7Preview: " + safePreview);
            lore.add("&7Status: " + statusColor + prettyStatus(tag.getStatus()));
            if (tag.getStatus() == TagStatus.REJECTED && tag.getRejectReason() != null) {
                lore.add("&7Reason: &f" + ColorUtil.capLoreText(tag.getRejectReason()));
            }
            if (active) {
                lore.add("");
                lore.add("&a&l\u2726 Currently equipped");
            } else if (inRandomPool) {
                lore.add("");
                lore.add("&d&l\u21BB In random rotation");
            }
            lore.add("");
            lore.add("&8\u25B8 Left-click &7to edit");
            if (tag.getStatus() == TagStatus.APPROVED) {
                if (randomActive) {
                    lore.add("&8\u25B8 &7Random mode is &d&lON&7 \u2014 manual select disabled");
                } else if (active) {
                    lore.add("&8\u25B8 Right-click &7to unselect");
                } else {
                    lore.add("&8\u25B8 Right-click &7to select");
                }
            }
            lore.add("&8\u25B8 Drop &7(&fQ&7) &7to delete");

            inv.setItem(slot, new ItemBuilder(mat)
                    .name(statusColor + "&l" + prettyStatus(tag.getStatus()))
                    .lore(lore)
                    .glow(active || inRandomPool)
                    .build());
            holder.putContext(slot, tag.getId());
            slot++;
        }

        int createSlot = cfg.guiSlot("tag-list", "create-slot");
        boolean canCreate = plugin.tagService().canOpenCreateMethod(data);

        List<String> createLore = new ArrayList<>();
        if (!canCreate) {
            if (data.hasPending()) createLore.add("&cYou already have a pending request.");
            else if (data.getTokens() <= 0) createLore.add("&cYou have no tokens left.");
            else if (data.activeTagCount() >= cfg.maxTagsPerPlayer()) createLore.add("&cMax tags reached.");
            else if (plugin.cooldown().isOnCooldown(data))
                createLore.add("&cCooldown: &f" + plugin.cooldown().formatDuration(plugin.cooldown().remainingSeconds(data)));
        } else if (data.isReservationActive()) {
            createLore.add("&7Continue your in-progress creation.");
        } else {
            createLore.add("&7Click to request a new tag.");
            createLore.add("&7Costs &f1 token&7.");
        }

        ItemStack createItem = canCreate
                ? ItemBuilder.icon(cfg.iconBase64("create-button"), Material.EMERALD).name("&a&l+ Create New Tag").lore(createLore).build()
                : ItemBuilder.icon(cfg.iconBase64("create-button-locked"), Material.REDSTONE).name("&c&l+ Create New Tag").lore(createLore).build();
        inv.setItem(createSlot, createItem);

        int backSlot = cfg.guiSlot("tag-list", "back-slot");
        inv.setItem(backSlot, new ItemBuilder(Material.ARROW).name("&7&lBack").build());

        long approvedCount = data.approvedTagCount();
        int randomSlot = cfg.guiSlot("tag-list", "random-slot");
        boolean randomAvailable = approvedCount >= cfg.randomMinTags();
        List<String> randomLore = new ArrayList<>();
        if (randomAvailable) {
            randomLore.add(cfg.rawMsg(data.isRandomTagEnabled() ? "random-entry-lore-on" : "random-entry-lore-off"));
        } else {
            randomLore.add(cfg.rawMsg("random-not-enough-tags").replace("{min}", String.valueOf(cfg.randomMinTags())));
        }
        inv.setItem(randomSlot, new ItemBuilder(randomAvailable ? Material.FIREWORK_STAR : Material.GRAY_DYE)
                .name(cfg.rawMsg("random-entry-name"))
                .lore(randomLore)
                .glow(data.isRandomTagEnabled())
                .build());

        GuiFrame.renderPageFooter(inv, cfg, "tag-list", currentPage, totalPages);
        GuiFrame.fillEmptyCheckered(inv, size, cfg);

        player.openInventory(inv);
    }

    private String prettyStatus(TagStatus status) {
        return switch (status) {
            case APPROVED -> "Approved";
            case PENDING -> "Pending";
            case REJECTED -> "Rejected";
        };
    }
}
