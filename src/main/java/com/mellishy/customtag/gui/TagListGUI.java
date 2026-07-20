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

public class TagListGUI {

    private final MellishyCustomTag plugin;

    public TagListGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        int size = cfg.guiSize("tag-list");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.TAG_LIST);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(cfg.guiTitle("tag-list")));
        holder.setInventory(inv);

        GuiFrame.border(inv, size, cfg);

        // While random rotation is actually in effect, there is no single "equipped" tag anymore -
        // a different one is picked per chat message (see ChatTagListener#resolveTag). Showing the
        // stale single active-tag as "Currently equipped" here would be misleading, so that whole
        // indicator is suppressed while random mode is live, and tags that are part of the rotation
        // pool get their own distinct indicator instead.
        boolean randomActive = data.isRandomTagEnabled() && data.approvedTagCount() >= cfg.randomMinTags();
        List<TagEntry> randomPool = randomActive ? data.resolveRandomPool() : List.of();

        GuiFrame.ContentGrid grid = GuiFrame.contentGrid(size);
        int slot = grid.startSlot();
        for (TagEntry tag : data.getTags()) {
            if (grid.isPastEnd(slot)) break; // safety, keep within the actual configured inventory size
            if ((slot + 1) % 9 == 0) slot += 2; // skip the right/left border columns

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
            // BUGFIX: this used to concatenate the raw, unfiltered tag text straight into the lore
            // line (later parsed with full ColorUtil.parse() by ItemBuilder). Every OTHER place a
            // tag is shown to any audience (AdminGUI, AdminPlayerTagsGUI, ChatTagListener,
            // MellishyPlaceholder) is careful to either strip it to plain text or run it through
            // parseForOthers() so an interactive MiniMessage payload (click/hover) can never be
            // smuggled in - this was the one inconsistent spot, letting a player embed a
            // <click:run_command:'...'> etc. into their own tag list's lore. Round-tripping through
            // parseForOthers() + toLegacyString() keeps every cosmetic color/gradient/format intact
            // (so the preview still looks exactly like the real rendered tag) while guaranteeing the
            // string handed to ItemBuilder.lore() (which re-parses it) no longer contains any
            // interactive tag syntax to begin with.
            String safePreview = ColorUtil.toLegacyString(ColorUtil.parseForOthers(tag.getRawText()));
            lore.add("&7Preview: " + safePreview);
            lore.add("&7Status: " + statusColor + prettyStatus(tag.getStatus()));
            if (tag.getStatus() == TagStatus.REJECTED && tag.getRejectReason() != null) {
                lore.add("&7Reason: &f" + tag.getRejectReason());
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
                // Manual selection has no effect while random rotation is live (chat always rolls
                // from the pool instead - see ChatTagListener), so don't invite a click that would
                // just say "selected" and do nothing. Tell the player why instead.
                if (randomActive) {
                    lore.add("&8\u25B8 &7Random mode is &d&lON&7 \u2014 manual select disabled");
                } else if (active) {
                    // Right-click is a toggle (see GuiListener#handleTagList) - once a tag is
                    // equipped, the same button unequips it again instead of being a dead end.
                    lore.add("&8\u25B8 Right-click &7to unselect");
                } else {
                    lore.add("&8\u25B8 Right-click &7to select");
                }
            }
            lore.add("&8\u25B8 Drop &7(&fQ&7) &7to delete");

            ItemStack item = new ItemBuilder(mat)
                    .name(statusColor + "&l" + prettyStatus(tag.getStatus()))
                    .lore(lore)
                    .glow(active || inRandomPool)
                    .build();

            inv.setItem(slot, item);
            holder.putContext(slot, tag.getId());
            slot++;
        }

        int createSlot = cfg.guiSlot("tag-list", "create-slot");
        boolean canCreate = plugin.tagService().canOpenCreateMethod(data);

        List<String> createLore = new ArrayList<>();
        if (!canCreate) {
            if (data.hasPending()) createLore.add("&cYou already have a pending request.");
            else if (data.getTokens() <= 0) createLore.add("&cYou have no tokens left.");
            // BUGFIX: same fix as MainMenuGUI#open - must mirror TagService#canOpenCreateMethod's
            // activeTagCount() check, not getTags().size() (which also counts REJECTED history and
            // could show this reason for the wrong cause, or hide the real one - e.g. a cooldown).
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

        // ---- random tag rotation entry point ----
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