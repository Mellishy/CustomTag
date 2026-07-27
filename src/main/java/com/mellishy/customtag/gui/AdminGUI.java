package com.mellishy.customtag.gui;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.config.GuiStateTheme;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Admin review queue.
 *
 * The whole menu is now colored from the same shared status-theme files used by the player menus
 * (see {@link com.mellishy.customtag.config.GuiStateManager}), instead of a hardcoded material pair:
 *   - zero pending requests -> themed GREEN checkerboard ("guiApproved.yml"), exactly the same
 *     pattern/style as everywhere else the plugin shows "approved/all good".
 *   - one or more pending requests -> themed ORANGE checkerboard ("guiPending.yml") as the
 *     background, with real request heads placed on top overwriting individual panes as they come
 *     in - not every pane turns orange/solid, only the checkerboard pattern does, matching the
 *     look of the rest of the plugin instead of a flat wall of one color.
 * This also fixes the original bug where the empty-state icon could collide with the border - it's
 * always placed at the true center of the interior grid, never a border/side slot.
 *
 * Each request entry shows plain, uncolored details only (player, exactly what they wrote, and
 * when) - never the tag rendered live - so a deliberately glitchy/obfuscated submission can't make
 * the admin menu itself look broken. Requests are always sorted oldest-first (FIFO), since the
 * backing player-data map has no guaranteed iteration order on its own.
 *
 * Silent moderation: a request can be rejected+removed WITHOUT ever sending the player a chat
 * message, via two distinct, explicit actions (see
 * {@link com.mellishy.customtag.service.TagService#rejectSilent} and
 * {@link com.mellishy.customtag.listener.GuiListener#handleAdminList}):
 *   - SHIFT+Right-click: silently removed, token IS refunded (a quiet courtesy removal).
 *   - Drop (&fQ&7): silently removed, token is NOT refunded (a real, silent penalty).
 * Both are always visible as separate lore lines so an admin never has to guess which button does
 * what, unlike the old single SHIFT+right-click action whose refund behaviour depended entirely on
 * a server-wide config flag the admin couldn't see from the GUI.
 *
 * ---- Pagination ----
 * On a small server the whole queue always fit in one grid and any overflow was simply invisible -
 * a request past the last slot never showed up anywhere until enough others were cleared. On a
 * large server it's normal to have dozens of pending requests at once, so the queue is paginated:
 * requests are still sorted oldest-first (FIFO) globally, then sliced into pages of
 * {@code maxSlots} each. Prev/Next buttons are greyed out AND fully inert (no-op) at the
 * first/last page - {@link com.mellishy.customtag.listener.GuiListener#handleAdminList} checks the
 * real page bounds before doing anything, it doesn't just rely on the icon looking disabled. The
 * separate "Page X/Y" filler item is gone; that info now lives directly in the GUI title instead,
 * since it's an ambient status readout, not something an admin needs to click.
 */
public class AdminGUI {

    private final MellishyCustomTag plugin;

    // Cached across calls to open() instead of allocating a new SimpleDateFormat every single time
    // the admin queue is opened/paginated - this menu can realistically be reopened dozens of times
    // a minute by a busy moderator. Safe to cache as a plain field (not thread-local/synchronized):
    // GUI code only ever runs on the main thread, so there's never concurrent access to it. Keyed by
    // pattern string so a live /customtag reload that changes messages.admin-date-format is picked
    // up immediately instead of keeping a stale formatter.
    private String cachedDatePattern;
    private SimpleDateFormat cachedDateFormat;

    public AdminGUI(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    private SimpleDateFormat dateFormat(String pattern) {
        if (cachedDateFormat == null || !pattern.equals(cachedDatePattern)) {
            cachedDatePattern = pattern;
            cachedDateFormat = new SimpleDateFormat(pattern);
        }
        return cachedDateFormat;
    }

    public void open(Player admin) {
        open(admin, 0);
    }

    public void open(Player admin, int page) {
        ConfigManager cfg = plugin.config();
        int size = cfg.guiSize("admin-list");

        List<PlayerData> pendingOwners = new ArrayList<>();
        for (PlayerData data : plugin.data().all().values()) {
            if (data.getPendingTag().isPresent()) pendingOwners.add(data);
        }
        // oldest request first - always, regardless of the backing map's iteration order
        pendingOwners.sort(Comparator.comparingLong(d -> d.getPendingTag().get().getCreatedAt()));

        GuiFrame.ContentGrid grid = GuiFrame.contentGrid(size);
        int perPage = GuiFrame.contentPerPage(size);
        int totalPending = pendingOwners.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalPending / (double) perPage));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        // Page/total-pending info now lives in the GUI title itself (computed up front, before the
        // inventory is created) instead of a separate clickable-looking item in the footer.
        // Defensively capped: an admin free-typing a very long gui.admin-list.title in config.yml
        // (plus this page suffix) could otherwise produce a title the client silently truncates or
        // renders oddly - trimming the CONFIGURED part here keeps the "[x/y]" page indicator, which
        // is the one part that actually needs to stay visible, always intact and readable.
        String rawTitle = cfg.guiTitle("admin-list");
        if (rawTitle.length() > 48) rawTitle = rawTitle.substring(0, 48);
        String title = rawTitle + " &7[" + (currentPage + 1) + "/" + totalPages + "]";

        MellishyInventoryHolder holder = new MellishyInventoryHolder(GuiType.ADMIN_LIST);
        Inventory inv = plugin.getServer().createInventory(holder, size, ColorUtil.parse(title));
        holder.setInventory(inv);
        holder.setPage(currentPage);
        holder.setTotalPages(totalPages);

        GuiStateTheme theme = pendingOwners.isEmpty() ? plugin.guiStates().approved() : plugin.guiStates().pending();
        GuiFrame.applyTheme(inv, size, theme);

        int backSlot = cfg.guiSlot("admin-list", "back-slot");
        inv.setItem(backSlot, new ItemBuilder(Material.BARRIER).name("&c&lClose").build());

        SimpleDateFormat dateFormat = dateFormat(cfg.adminDateFormat());

        int fromIndex = currentPage * perPage;
        int toIndex = Math.min(fromIndex + perPage, totalPending);
        List<PlayerData> pageSlice = totalPending == 0 ? List.of() : pendingOwners.subList(fromIndex, toIndex);

        int slot = grid.startSlot();
        for (PlayerData data : pageSlice) {
            if (grid.isPastEnd(slot)) break; // defense-in-depth - pageSlice is already sized to fit, but never trust that alone
            slot = GuiFrame.skipBorderColumn(slot);

            TagEntry tag = data.getPendingTag().get();
            String plainText = ColorUtil.stripToPlain(tag.getRawText());
            if (plainText.isBlank()) plainText = "(blank)";

            List<String> lore = new ArrayList<>();
            lore.add(cfg.rawMsg("admin-request-player").replace("{player}", data.getLastKnownName()));
            lore.add(cfg.rawMsg("admin-request-text").replace("{text}", plainText));
            lore.add(cfg.rawMsg("admin-request-date").replace("{date}", dateFormat.format(new Date(tag.getCreatedAt()))));
            lore.add("");
            lore.add(cfg.rawMsg("admin-request-actions-approve"));
            lore.add(cfg.rawMsg("admin-request-actions-reject"));
            lore.add(cfg.rawMsg("admin-request-actions-silent-refund"));
            lore.add(cfg.rawMsg("admin-request-actions-silent-no-refund"));

            ItemStack item = ItemBuilder.playerHead(data.getUuid())
                    .name("&e&l" + data.getLastKnownName())
                    .lore(lore)
                    .build();
            inv.setItem(slot, item);
            holder.putContext(slot, data.getUuid() + ":" + tag.getId());
            slot++;
        }

        if (totalPending == 0) {
            int rows = size / 9;
            int centerRow = rows / 2;
            int centerSlot = centerRow * 9 + 4; // true center column, never a border/side slot
            inv.setItem(centerSlot, new ItemBuilder(theme.fillerAlt())
                    .name(cfg.rawMsg("admin-no-requests-name"))
                    .lore(List.of(cfg.rawMsg("admin-no-requests-lore")))
                    .glow(theme.glow())
                    .build());
        }

        renderFooter(inv, cfg, currentPage, totalPages, totalPending);

        admin.openInventory(inv);
    }

    private void renderFooter(Inventory inv, ConfigManager cfg, int currentPage, int totalPages, int totalPending) {
        GuiFrame.renderPageFooter(inv, cfg, "admin-list", currentPage, totalPages);
        // Total pending rides on the next-page button lore when navigation is available
        int nextSlot = cfg.guiSlot("admin-list", "next-page-slot");
        boolean hasNext = currentPage < totalPages - 1;
        if (hasNext) {
            inv.setItem(nextSlot, new ItemBuilder(Material.ARROW)
                    .name("&e&lNext Page \u25B6")
                    .lore(List.of("&7Total pending: &f" + totalPending))
                    .build());
        }
    }
}