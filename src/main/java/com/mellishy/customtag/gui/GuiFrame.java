package com.mellishy.customtag.gui;

import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.config.GuiStateTheme;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Draws a themed border frame (top/bottom rows solid, side columns lighter) and can optionally
 * pad the remaining interior with a neutral filler pane so a menu never looks "half built".
 *
 * Every material used here is read from config.yml (gui.theme.*) instead of being hardcoded, so
 * server owners can re-theme every menu in the plugin (any colored glass, or any material at all)
 * without touching any code. An invalid/unknown material name always falls back safely to a
 * sensible default rather than breaking the menu.
 */
public final class GuiFrame {

    private GuiFrame() {}

    public static void border(Inventory inv, int size, ConfigManager cfg) {
        ItemStack edge = pane(cfg.themeBorderMaterial(), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack side = pane(cfg.themeSideMaterial(), Material.GRAY_STAINED_GLASS_PANE);

        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int col = i % 9;
            int row = i / 9;
            boolean topOrBottom = row == 0 || row == rows - 1;
            boolean leftOrRight = col == 0 || col == 8;
            if (topOrBottom) {
                inv.setItem(i, edge);
            } else if (leftOrRight) {
                inv.setItem(i, side);
            }
        }
    }

    /** Fills every currently-empty slot (border already drawn, buttons not yet placed) with a themed filler pane. */
    public static void fillEmpty(Inventory inv, int size, ConfigManager cfg) {
        fillEmpty(inv, size, materialOrFallback(cfg.themeFillerMaterial(), Material.LIGHT_GRAY_STAINED_GLASS_PANE));
    }

    public static void fillEmpty(Inventory inv, int size, Material material) {
        ItemStack filler = new ItemBuilder(material).name(" ").build();
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }

    /** Checkerboards the global theme's two filler colors (gui.theme.filler-material / filler-material-alt). */
    public static void fillEmptyCheckered(Inventory inv, int size, ConfigManager cfg) {
        Material main = materialOrFallback(cfg.themeFillerMaterial(), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        Material alt = materialOrFallback(cfg.themeFillerAltMaterial(), Material.WHITE_STAINED_GLASS_PANE);
        fillEmptyCheckered(inv, size, main, alt);
    }

    /**
     * Same as {@link #fillEmpty(Inventory, int, Material)}, but alternates between two panes in a
     * checkerboard pattern instead of one flat color - a plain single-color fill is what made large
     * menus (e.g. the admin queue) look like an unfinished wall of identical glass. Falls back to a
     * single material automatically if {@code alt} couldn't be resolved.
     */
    public static void fillEmptyCheckered(Inventory inv, int size, Material main, Material alt) {
        ItemStack a = new ItemBuilder(main).name(" ").build();
        ItemStack b = new ItemBuilder(alt).name(" ").build();
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) != null) continue;
            int row = i / 9;
            int col = i % 9;
            inv.setItem(i, (row + col) % 2 == 0 ? a : b);
        }
    }

    /** Same as {@link #border(Inventory, int, ConfigManager)} but colored from a status theme instead of the global theme. */
    public static void border(Inventory inv, int size, GuiStateTheme theme) {
        ItemStack edge = new ItemBuilder(theme.border()).name(" ").build();
        ItemStack side = new ItemBuilder(theme.side()).name(" ").build();

        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int col = i % 9;
            int row = i / 9;
            boolean topOrBottom = row == 0 || row == rows - 1;
            boolean leftOrRight = col == 0 || col == 8;
            if (topOrBottom) {
                inv.setItem(i, edge);
            } else if (leftOrRight) {
                inv.setItem(i, side);
            }
        }
    }

    /** Same as {@link #fillEmptyCheckered(Inventory, int, ConfigManager)} but colored from a status theme instead of the global theme. */
    public static void fillEmptyCheckered(Inventory inv, int size, GuiStateTheme theme) {
        fillEmptyCheckered(inv, size, theme.filler(), theme.fillerAlt());
    }

    /**
     * Draws BOTH the border and the checkerboard interior fill for a whole status-themed menu in one
     * call - this is what makes the main menu / tag list / admin queue genuinely change color (gray,
     * orange, green or red) depending on the player's current request status, instead of only a
     * single accent item changing color while the rest of the menu stays neutral.
     */
    public static void applyTheme(Inventory inv, int size, GuiStateTheme theme) {
        border(inv, size, theme);
        fillEmptyCheckered(inv, size, theme);
    }

    /**
     * Describes the interior "content" grid of a bordered menu for a GIVEN inventory size: the first
     * usable interior slot, and the last interior slot of the last interior row. Interior rows are
     * every row except the very top and very bottom border row - footer/back buttons (create-slot,
     * back-slot, prev/next-page, ...) live INSIDE that bottom border row, overwriting specific cells
     * after the border is drawn (exactly like {@link AdminGUI}'s own {@code perPage} calculation
     * already does), so they are never part of this content grid to begin with.
     *
     * Every item-placement loop in the GUI classes (tag list, random settings, admin player-tags)
     * MUST derive its bounds from this instead of a hardcoded constant like {@code 43}. That constant
     * only happened to be correct for the shipped default of size=54 (6 rows) - if a server owner
     * shrinks a menu's {@code size} in config.yml, a hardcoded bound could make
     * {@code Inventory#setItem} be called with a slot index >= size, throwing an
     * {@code ArrayIndexOutOfBoundsException} and breaking the menu entirely. Deriving the bound from
     * the real size here means any valid, 9-wide, at-least-3-row size just works.
     */
    public record ContentGrid(int startSlot, int lastSlot) {
        /** True once {@code slot} has moved past the last usable interior row for this grid. */
        public boolean isPastEnd(int slot) {
            return slot > lastSlot;
        }
    }

    /** @param size the inventory's total slot count (must be a positive multiple of 9). */
    public static ContentGrid contentGrid(int size) {
        int rows = Math.max(1, size / 9);
        int interiorRows = rows - 2; // every row except the top and bottom border row
        if (interiorRows < 1) {
            // menu too small to have any real interior row (e.g. the 9-slot admin-reason menu) -
            // return an already-exhausted grid so callers' loops simply place nothing instead of
            // computing a negative/invalid slot.
            return new ContentGrid(10, 9);
        }
        int startSlot = 10; // row 1, column 1 - first interior slot, right after the top-left border corner
        int lastSlot = interiorRows * 9 + 7; // last interior column (7) of the last interior row
        return new ContentGrid(startSlot, lastSlot);
    }

    /** Resolves a config material name, falling back safely (blank/invalid name never breaks a menu). */
    public static Material materialOrFallback(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            Material m = Material.matchMaterial(name.trim());
            return m != null ? m : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static ItemStack pane(String configuredName, Material fallback) {
        return new ItemBuilder(materialOrFallback(configuredName, fallback)).name(" ").build();
    }
}