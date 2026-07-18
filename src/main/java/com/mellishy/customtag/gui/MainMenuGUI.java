package com.mellishy.customtag.gui;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.config.GuiStateTheme;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout (default 45 slots / 5 rows):
 *   row 0: [ . . . . CREATE . LIST . . ]   <- create button, one gap, custom-tag-list button
 *   row 1: (empty, breathing room)
 *   row 2: [ . . . . HEAD  . . . . ]       <- player profile head, two rows above the exit button
 *   row 3: (empty)
 *   row 4: [ . . . . EXIT  . . . . ]       <- clean single exit button on the bottom row
 * Every slot position is config-driven (gui.main-menu.*), so server owners can rearrange this freely.
 */
public class MainMenuGUI {

    private final MellishyCustomTag plugin;

    public MainMenuGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        int size = cfg.guiSize("main-menu");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.MAIN_MENU);
        Inventory inv = plugin.getServer().createInventory(holder, size, com.mellishy.customtag.util.ColorUtil.parse(cfg.guiTitle("main-menu")));
        holder.setInventory(inv);

        int headSlot = cfg.guiSlot("main-menu", "head-slot");
        int exitSlot = cfg.guiSlot("main-menu", "exit-slot");
        int createSlot = cfg.guiSlot("main-menu", "create-slot");
        int listSlot = cfg.guiSlot("main-menu", "list-slot");

        // ---- profile head ----
        String status;
        String detail;
        GuiStateTheme theme;
        var pending = data.getPendingTag();
        if (pending.isPresent()) {
            status = cfg.loreValue("status-pending");
            detail = cfg.loreValue("detail-pending");
            theme = plugin.guiStates().pending();
        } else {
            var lastRejected = data.getTags().stream()
                    .filter(t -> t.getStatus() == TagStatus.REJECTED)
                    .reduce((a, b) -> b);
            var anyApproved = data.getTags().stream().anyMatch(t -> t.getStatus() == TagStatus.APPROVED);
            if (data.getTags().isEmpty()) {
                status = cfg.loreValue("status-none");
                detail = cfg.loreValue("detail-none");
                theme = plugin.guiStates().noRequest();
            } else if (anyApproved && lastRejected.isEmpty()) {
                status = cfg.loreValue("status-approved");
                detail = cfg.loreValue("detail-approved");
                theme = plugin.guiStates().approved();
            } else if (lastRejected.isPresent()) {
                status = cfg.loreValue("status-rejected");
                detail = cfg.loreValue("detail-rejected").replace("{reason}", lastRejected.get().getRejectReason() == null ? "-" : lastRejected.get().getRejectReason());
                theme = plugin.guiStates().rejected();
            } else {
                status = cfg.loreValue("status-none");
                detail = cfg.loreValue("detail-none");
                theme = plugin.guiStates().noRequest();
            }
        }

        // the whole menu's glass frame changes color with the player's status (gray/orange/green/red) -
        // see guiNoRequest.yml / guiPending.yml / guiApproved.yml / guiReject.yml
        GuiFrame.applyTheme(inv, size, theme);

        List<String> lore = new ArrayList<>();
        for (String line : cfg.profileLore()) {
            lore.add(line
                    .replace("{tokens}", String.valueOf(data.getTokens()))
                    .replace("{tag-count}", String.valueOf(data.getTags().size()))
                    .replace("{max-tags}", String.valueOf(cfg.maxTagsPerPlayer()))
                    .replace("{status}", status)
                    .replace("{status-detail}", detail));
        }

        ItemStack head = ItemBuilder.playerHead(player.getUniqueId())
                .name("&e&l" + player.getName())
                .lore(lore)
                .build();
        inv.setItem(headSlot, head);

        // ---- create button (base64-customizable, turns red and click-blocked when out of tokens) ----
        boolean canCreate = plugin.tagService().canOpenCreateMethod(data);
        ItemStack createItem;
        if (canCreate) {
            List<String> createLore = data.isReservationActive()
                    ? List.of("&7You have a creation in progress.", "&7Click to continue where you left off.")
                    : List.of("&7Click to start creating a new tag.", "&7Costs &f1 token&7.");
            createItem = ItemBuilder.icon(cfg.iconBase64("create-button"), Material.EMERALD_BLOCK)
                    .name("&a&l+ Create New Tag")
                    .lore(createLore)
                    .build();
        } else {
            List<String> reasons = new ArrayList<>();
            if (data.hasPending()) reasons.add("&cYou already have a pending request.");
            else if (data.getTokens() <= 0) reasons.add("&cYou have no tokens left.");
            else if (data.getTags().size() >= cfg.maxTagsPerPlayer()) reasons.add("&cYou reached the max amount of tags.");
            else if (plugin.cooldown().isOnCooldown(data))
                reasons.add("&cCooldown: &f" + plugin.cooldown().formatDuration(plugin.cooldown().remainingSeconds(data)));
            createItem = ItemBuilder.icon(cfg.iconBase64("create-button-locked"), Material.REDSTONE_BLOCK)
                    .name("&c&l+ Create New Tag")
                    .lore(reasons)
                    .build();
        }
        inv.setItem(createSlot, createItem);

        // ---- custom tag list button ----
        ItemStack listItem = ItemBuilder.icon(cfg.iconBase64("list-button"), Material.NAME_TAG)
                .name("&b&l\u2630 My Custom Tags")
                .lore(List.of(
                        "&7View, edit and select every",
                        "&7tag you've ever requested.",
                        "",
                        "&7" + data.getTags().size() + "&8/&7" + cfg.maxTagsPerPlayer() + " &7slots used"
                ))
                .build();
        inv.setItem(listSlot, listItem);

        // ---- exit ----
        ItemStack exit = new ItemBuilder(Material.BARRIER).name("&c&lClose").build();
        inv.setItem(exitSlot, exit);

        player.openInventory(inv);
    }
}
