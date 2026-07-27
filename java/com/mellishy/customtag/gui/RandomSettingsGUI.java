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
 * Lets a player turn "random tag" mode on/off, and - once they own enough approved tags - pick
 * exactly which of them are allowed to be randomly chosen per chat message (see
 * {@link com.mellishy.customtag.listener.ChatTagListener} for where the pick actually happens).
 *
 * Rules (all thresholds configurable under random-tag.* in config.yml, nothing hardcoded):
 *   - fewer than {@code random-tag.min-tags} APPROVED tags -> the feature can't be turned on at all.
 *   - {@code random-tag.min-tags} or more -> the toggle works; with no subset picked, EVERY approved
 *     tag participates automatically (the simplest possible behaviour for small tag lists).
 *   - {@code random-tag.subset-unlock-tags} or more -> picking a deliberate subset here actually
 *     matters/is worth using; selected tags get an enchant glow and a distinct lore line so it's
 *     obvious at a glance which ones are in the rotation.
 */
public class RandomSettingsGUI {

    private final MellishyCustomTag plugin;

    public RandomSettingsGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        ConfigManager cfg = plugin.config();
        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        int size = cfg.guiSize("random-settings");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.RANDOM_SETTINGS);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(cfg.guiTitle("random-settings")));
        holder.setInventory(inv);

        GuiFrame.border(inv, size, cfg);
        GuiFrame.fillEmptyCheckered(inv, size, cfg);

        List<TagEntry> approved = data.getTags().stream().filter(t -> t.getStatus() == TagStatus.APPROVED).toList();
        boolean canEnable = approved.size() >= cfg.randomMinTags();
        boolean subsetUnlocked = approved.size() >= cfg.randomSubsetUnlockTags();

        // ---- master toggle ----
        int toggleSlot = cfg.guiSlot("random-settings", "toggle-slot");
        boolean enabled = data.isRandomTagEnabled() && canEnable;
        List<String> toggleLore = new ArrayList<>();
        if (!canEnable) {
            toggleLore.add(cfg.rawMsg("random-not-enough-tags").replace("{min}", String.valueOf(cfg.randomMinTags())));
        } else {
            toggleLore.add(cfg.rawMsg(enabled ? "random-toggle-lore-on" : "random-toggle-lore-off"));
        }
        inv.setItem(toggleSlot, new ItemBuilder(canEnable ? (enabled ? Material.LIME_DYE : Material.GRAY_DYE) : Material.BARRIER)
                .name(cfg.rawMsg(enabled ? "random-toggle-name-on" : "random-toggle-name-off"))
                .lore(toggleLore)
                .glow(enabled)
                .build());
        holder.putContext(toggleSlot, "toggle");

        // ---- per-tag selection grid ----
        // "Selected" (glowing) is only ever shown while the master toggle is actually ON - a subset
        // picked earlier stays saved in data, but visually showing it as "selected" while random mode
        // itself is off would contradict the "nothing is selected" state the player sees at a glance.
        // starts one row further in than the plugin's other tag grids (row 2 instead of row 1) to
        // leave room for the master toggle button in the row above - still derived from the real
        // configured size, just offset by one interior row.
        GuiFrame.ContentGrid grid = GuiFrame.contentGrid(size);
        int slot = grid.startSlot() + 9;
        for (TagEntry tag : approved) {
            if (grid.isPastEnd(slot)) break;
            if ((slot + 1) % 9 == 0) slot += 2;

            boolean selected = enabled && data.getRandomTagPool().contains(tag.getId());
            List<String> lore = new ArrayList<>();
            lore.add(cfg.rawMsg("random-tag-preview").replace("{tag}", ColorUtil.stripToPlain(tag.getRawText())));
            lore.add("");
            if (!enabled) {
                lore.add(cfg.rawMsg("random-tag-not-selected"));
            } else if (selected) {
                lore.add(cfg.rawMsg("random-tag-selected"));
            } else if (data.getRandomTagPool().isEmpty()) {
                // nothing deliberately picked yet - every approved tag is currently included by default
                lore.add(cfg.rawMsg("random-tag-included-default"));
            } else {
                lore.add(cfg.rawMsg("random-tag-not-selected"));
            }
            if (subsetUnlocked) {
                lore.add(cfg.rawMsg("random-tag-click-hint"));
            }

            ItemStack item = new ItemBuilder(Material.NAME_TAG)
                    .name((selected ? "&a&l" : "&7") + ColorUtil.stripToPlain(tag.getRawText()))
                    .lore(lore)
                    .glow(selected)
                    .build();
            inv.setItem(slot, item);
            holder.putContext(slot, tag.getId());
            slot++;
        }

        int backSlot = cfg.guiSlot("random-settings", "back-slot");
        inv.setItem(backSlot, new ItemBuilder(Material.ARROW).name("&7&lBack").build());

        player.openInventory(inv);
    }
}