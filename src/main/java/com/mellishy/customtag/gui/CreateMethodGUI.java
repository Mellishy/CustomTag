package com.mellishy.customtag.gui;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

public class CreateMethodGUI {

    /** Where the player opened this menu from, so the Back button can return them to the right place. */
    public enum Origin { MAIN_MENU, TAG_LIST }

    /** Context-slot markers stored on the holder (both are negative so they never collide with a real inventory slot). */
    private static final int CTX_EDITING_ID = -1;
    private static final int CTX_ORIGIN = -2;

    private final MellishyCustomTag plugin;

    public CreateMethodGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    /**
     * @param editingTagId null when creating brand-new, or the id of the tag being re-edited.
     * @param origin       which menu the player pressed "Create"/"Edit" from - Back returns them there
     *                     instead of always dropping back to the tag list (that mismatch was the
     *                     original "Back button" bug).
     */
    public void open(Player player, String editingTagId, Origin origin) {
        ConfigManager cfg = plugin.config();
        int size = cfg.guiSize("method-menu");
        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.CREATE_METHOD);
        if (editingTagId != null) holder.putContext(CTX_EDITING_ID, editingTagId);
        holder.putContext(CTX_ORIGIN, origin.name());
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(cfg.guiTitle("method-menu")));
        holder.setInventory(inv);

        GuiFrame.border(inv, size, cfg);

        int bookSlot = cfg.guiSlot("method-menu", "book-slot");
        int chatSlot = cfg.guiSlot("method-menu", "chat-slot");
        int backSlot = cfg.guiSlot("method-menu", "back-slot");

        inv.setItem(bookSlot, new ItemBuilder(Material.WRITTEN_BOOK)
                .name("&e&l\u2711 Use a Book")
                .lore(List.of(
                        "&7A clean in-game book opens.",
                        "&7Write your tag on page &f2&7,",
                        "&7then click &fDone&7 &7(or &fSign&7)",
                        "&7to submit it for review."
                ))
                .build());

        inv.setItem(chatSlot, new ItemBuilder(Material.PAPER)
                .name("&b&l\u270E Use Chat")
                .lore(List.of(
                        "&7Type your tag directly in chat.",
                        "&7Type &ccancel&7 anytime to abort."
                ))
                .build());

        inv.setItem(backSlot, new ItemBuilder(Material.ARROW)
                .name("&7&lBack")
                .lore(List.of("&7Return to where you came from."))
                .build());

        GuiFrame.fillEmptyCheckered(inv, size, cfg);

        player.openInventory(inv);
    }

    public static Origin originOf(MellishyInventoryHolder holder) {
        String raw = holder.getContext(CTX_ORIGIN);
        try {
            return raw != null ? Origin.valueOf(raw) : Origin.TAG_LIST;
        } catch (IllegalArgumentException ex) {
            return Origin.TAG_LIST;
        }
    }

    public static String editingIdOf(MellishyInventoryHolder holder) {
        return holder.getContext(CTX_EDITING_ID);
    }
}
