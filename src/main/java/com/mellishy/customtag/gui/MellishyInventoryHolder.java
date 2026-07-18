package com.mellishy.customtag.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Every custom GUI inventory in this plugin uses this holder so the InventoryClickEvent
 * listener can reliably identify which menu was clicked (and pass small bits of context,
 * e.g. which tag-id a slot represents) without any hacky title-string matching.
 */
public class MellishyInventoryHolder implements InventoryHolder {

    private final GuiType type;
    private Inventory inventory;
    private final Map<Integer, String> slotContext = new HashMap<>();
    /** Zero-based page index, currently only used by the paginated admin queue. */
    private int page;
    /** Total page count for this inventory instance (always >= 1) - lets the listener enforce real
     *  first/last-page bounds on Prev/Next clicks instead of trusting a greyed-out icon alone. */
    private int totalPages = 1;

    public MellishyInventoryHolder(GuiType type) {
        this.type = type;
    }

    public GuiType getType() {
        return type;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public void putContext(int slot, String value) {
        slotContext.put(slot, value);
    }

    public String getContext(int slot) {
        return slotContext.get(slot);
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}