package com.mellishy.customtag.gui;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class AdminReasonGUI {

    private final MellishyCustomTag plugin;

    public AdminReasonGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    /** target = "<playerUuid>:<tagId>" being rejected. */
    public void open(Player admin, String target) {
        ConfigManager cfg = plugin.config();
        int size = cfg.guiSize("admin-reason");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.ADMIN_REASON);
        holder.putContext(-1, target);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(cfg.guiTitle("admin-reason")));
        holder.setInventory(inv);

        ItemStack filler = new ItemBuilder(GuiFrame.materialOrFallback(cfg.themeFillerMaterial(), Material.BLACK_STAINED_GLASS_PANE)).name(" ").build();
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        List<String> presets = cfg.rejectPresets();
        int[] presetSlots = { cfg.guiSlot("admin-reason", "preset1-slot"), cfg.guiSlot("admin-reason", "preset2-slot") };
        for (int i = 0; i < presets.size() && i < presetSlots.length; i++) {
            inv.setItem(presetSlots[i], new ItemBuilder(Material.PAPER)
                    .name("&e&lPreset " + (i + 1))
                    .lore(List.of(presets.get(i)))
                    .build());
            holder.putContext(presetSlots[i], "preset:" + i);
        }

        int customSlot = cfg.guiSlot("admin-reason", "custom-slot");
        inv.setItem(customSlot, new ItemBuilder(Material.WRITABLE_BOOK)
                .name("&b&lCustom Reason")
                .lore(List.of("&7Click to type a custom reason in chat."))
                .build());
        holder.putContext(customSlot, "custom");

        admin.openInventory(inv);
    }
}
