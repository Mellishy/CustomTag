package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.gui.AdminGUI;
import com.mellishy.customtag.gui.AdminPlayerTagsGUI;
import com.mellishy.customtag.gui.AdminReasonGUI;
import com.mellishy.customtag.gui.CreateMethodGUI;
import com.mellishy.customtag.gui.MainMenuGUI;
import com.mellishy.customtag.gui.MellishyInventoryHolder;
import com.mellishy.customtag.gui.RandomSettingsGUI;
import com.mellishy.customtag.gui.TagListGUI;
import com.mellishy.customtag.util.ColorUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

public class GuiListener implements Listener {

    private final MellishyCustomTag plugin;
    private final MainMenuGUI mainMenuGUI;
    private final TagListGUI tagListGUI;
    private final CreateMethodGUI createMethodGUI;
    private final AdminGUI adminGUI;
    private final AdminReasonGUI adminReasonGUI;
    private final RandomSettingsGUI randomSettingsGUI;
    private final AdminPlayerTagsGUI adminPlayerTagsGUI;

    public GuiListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
        this.mainMenuGUI = new MainMenuGUI(plugin);
        this.tagListGUI = new TagListGUI(plugin);
        this.createMethodGUI = new CreateMethodGUI(plugin);
        this.adminGUI = new AdminGUI(plugin);
        this.adminReasonGUI = new AdminReasonGUI(plugin);
        this.randomSettingsGUI = new RandomSettingsGUI(plugin);
        this.adminPlayerTagsGUI = new AdminPlayerTagsGUI(plugin);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MellishyInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getClickedInventory() != null && event.getClickedInventory().getHolder() instanceof MellishyInventoryHolder holder)) {
            // allow clicks in the player's own inventory while a Mellishy GUI is open, but block shift-clicks moving items in
            if (event.getView().getTopInventory().getHolder() instanceof MellishyInventoryHolder) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();
        ClickType click = event.getClick();

        switch (holder.getType()) {
            case MAIN_MENU -> handleMainMenu(player, slot);
            case TAG_LIST -> handleTagList(player, holder, slot, click);
            case CREATE_METHOD -> handleCreateMethod(player, holder, slot);
            case ADMIN_LIST -> { if (requireStaff(player)) handleAdminList(player, holder, slot, click); }
            case ADMIN_REASON -> { if (requireStaff(player)) handleAdminReason(player, holder, slot); }
            case RANDOM_SETTINGS -> handleRandomSettings(player, holder, slot);
            case ADMIN_PLAYER_TAGS -> { if (requireStaff(player)) handleAdminPlayerTags(player, holder, slot, click); }
        }
    }

    /**
     * Re-checks the staff permission on every admin click, not just when the menu was opened.
     * An open inventory outlives the permission that opened it: a staff member who is demoted,
     * has their LuckPerms group changed, or is de-opped while the queue is on screen otherwise
     * keeps full approve/reject/silent-delete power over every request for as long as they simply
     * do not close the window.
     */
    private boolean requireStaff(Player player) {
        if (plugin.platform().permissions().canStaff(player, "queue")) return true;
        player.sendMessage(ColorUtil.parse(plugin.config().msg("no-permission")));
        player.closeInventory();
        return false;
    }

    /**
     * Opens a menu on the NEXT tick instead of directly inside {@link InventoryClickEvent}.
     * Bukkit is still mid-way through processing the click when a handler runs - swapping the
     * player's open inventory underneath it desynchronises the client from the server's view of
     * the window, which shows up as ghost items, a menu that will not close, or the click landing
     * on the newly-drawn menu. Deferring by one tick lets the click finish first.
     */
    private void reopen(Runnable open) {
        plugin.getServer().getScheduler().runTask(plugin, open);
    }

    private void handleMainMenu(Player player, int slot) {
        ConfigManager cfg = plugin.config();
        int headSlot = cfg.guiSlot("main-menu", "head-slot");
        int exitSlot = cfg.guiSlot("main-menu", "exit-slot");
        int createSlot = cfg.guiSlot("main-menu", "create-slot");
        int listSlot = cfg.guiSlot("main-menu", "list-slot");

        if (slot == exitSlot) {
            player.closeInventory();
        } else if (slot == headSlot || slot == listSlot) {
            reopen(() -> tagListGUI.open(player));
        } else if (slot == createSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (plugin.tagService().canOpenCreateMethod(data)) {
                reopen(() -> createMethodGUI.open(player, null, CreateMethodGUI.Origin.MAIN_MENU));
            } else {
                player.sendMessage(ColorUtil.parse(cfg.msg("no-tokens")));
            }
        }
    }

    private void handleTagList(Player player, MellishyInventoryHolder holder, int slot, ClickType click) {
        ConfigManager cfg = plugin.config();
        int createSlot = cfg.guiSlot("tag-list", "create-slot");
        int backSlot = cfg.guiSlot("tag-list", "back-slot");
        int randomSlot = cfg.guiSlot("tag-list", "random-slot");
        int prevSlot = cfg.guiSlot("tag-list", "prev-page-slot");
        int nextSlot = cfg.guiSlot("tag-list", "next-page-slot");
        int page = holder.getPage();

        if (slot == backSlot) {
            reopen(() -> mainMenuGUI.open(player));
            return;
        }
        if (slot == prevSlot) {
            if (page > 0) reopen(() -> tagListGUI.open(player, page - 1));
            return;
        }
        if (slot == nextSlot) {
            if (page + 1 < holder.getTotalPages()) reopen(() -> tagListGUI.open(player, page + 1));
            return;
        }
        if (slot == randomSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (data.approvedTagCount() >= cfg.randomMinTags()) {
                reopen(() -> randomSettingsGUI.open(player));
            } else {
                player.sendMessage(ColorUtil.parse(cfg.msg("random-not-enough-tags").replace("{min}", String.valueOf(cfg.randomMinTags()))));
            }
            return;
        }
        if (slot == createSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (plugin.tagService().canOpenCreateMethod(data)) {
                reopen(() -> createMethodGUI.open(player, null, CreateMethodGUI.Origin.TAG_LIST));
            } else {
                reopen(() -> tagListGUI.open(player, page));
            }
            return;
        }

        String tagId = holder.getContext(slot);
        if (tagId == null) return;

        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
        var opt = data.getTagById(tagId);
        if (opt.isEmpty()) return;

        switch (click) {
            case LEFT -> {
                if (opt.get().getStatus() == TagStatus.PENDING) {
                    player.sendMessage(ColorUtil.parse(cfg.msg("request-pending-block")));
                    return;
                }
                reopen(() -> createMethodGUI.open(player, tagId, CreateMethodGUI.Origin.TAG_LIST));
            }
            case RIGHT -> {
                if (tagId.equals(data.getActiveTagId())) {
                    plugin.tagService().unselectTag(player, tagId);
                } else {
                    plugin.tagService().selectTag(player, tagId);
                }
                reopen(() -> tagListGUI.open(player, page));
            }
            case DROP, CONTROL_DROP -> {
                plugin.tagService().deleteTag(player, tagId);
                reopen(() -> tagListGUI.open(player, page));
            }
            default -> {}
        }
    }

    private void handleCreateMethod(Player player, MellishyInventoryHolder holder, int slot) {
        ConfigManager cfg = plugin.config();
        int bookSlot = cfg.guiSlot("method-menu", "book-slot");
        int chatSlot = cfg.guiSlot("method-menu", "chat-slot");
        int backSlot = cfg.guiSlot("method-menu", "back-slot");
        String editingId = CreateMethodGUI.editingIdOf(holder);
        CreateMethodGUI.Origin origin = CreateMethodGUI.originOf(holder);

        if (slot == backSlot) {
            // Bug fix: this used to always reopen the tag list, even for players who opened
            // "Create" straight from the main menu - now it returns to wherever they came from.
            if (origin == CreateMethodGUI.Origin.MAIN_MENU) {
                reopen(() -> mainMenuGUI.open(player));
            } else {
                reopen(() -> tagListGUI.open(player));
            }
        } else if (slot == bookSlot) {
            if (editingId == null) {
                String reservationId = plugin.tagService().reserveForCreation(player);
                if (reservationId == null) {
                    player.closeInventory();
                    return; // reserveForCreation already sent the player the reason
                }
                player.closeInventory();
                plugin.bookEdit().giveBook(player, "new", reservationId);
            } else {
                player.closeInventory();
                plugin.bookEdit().giveBook(player, editingId, null);
            }
        } else if (slot == chatSlot) {
            if (editingId == null) {
                String reservationId = plugin.tagService().reserveForCreation(player);
                if (reservationId == null) {
                    player.closeInventory();
                    return;
                }
                player.closeInventory();
                plugin.chatInput().await(player, ChatInputListener.InputType.CREATE_TAG, reservationId);
            } else {
                player.closeInventory();
                plugin.chatInput().await(player, ChatInputListener.InputType.EDIT_TAG, editingId);
            }
            player.sendMessage(ColorUtil.parse(cfg.msg("chat-input-prompt")));
        }
    }

    private void handleRandomSettings(Player player, MellishyInventoryHolder holder, int slot) {
        ConfigManager cfg = plugin.config();
        int toggleSlot = cfg.guiSlot("random-settings", "toggle-slot");
        int backSlot = cfg.guiSlot("random-settings", "back-slot");

        if (slot == backSlot) {
            reopen(() -> tagListGUI.open(player));
            return;
        }

        PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());

        if (slot == toggleSlot) {
            if (!data.isRandomTagEnabled() && data.approvedTagCount() < cfg.randomMinTags()) {
                player.sendMessage(ColorUtil.parse(cfg.msg("random-not-enough-tags").replace("{min}", String.valueOf(cfg.randomMinTags()))));
                return;
            }
            data.setRandomTagEnabled(!data.isRandomTagEnabled());
            plugin.data().save(data);
            reopen(() -> randomSettingsGUI.open(player));
            return;
        }

        String tagId = holder.getContext(slot);
        if (tagId == null || tagId.equals("toggle")) return;

        // BUGFIX: subset selection is only ever advertised (the "click to select" lore line in
        // RandomSettingsGUI) once the player has at least random-tag.subset-unlock-tags approved
        // tags - but this handler had no matching guard, so a click still silently narrowed the
        // player's random pool even below that threshold, contradicting what the GUI showed them.
        // Mirror RandomSettingsGUI#open's subsetUnlocked check exactly.
        if (data.approvedTagCount() < cfg.randomSubsetUnlockTags()) return;

        if (data.getRandomTagPool().contains(tagId)) {
            data.getRandomTagPool().remove(tagId);
        } else {
            data.getRandomTagPool().add(tagId);
        }
        plugin.data().save(data);
        reopen(() -> randomSettingsGUI.open(player));
    }

    private void handleAdminList(Player player, MellishyInventoryHolder holder, int slot, ClickType click) {
        ConfigManager cfg = plugin.config();
        int backSlot = cfg.guiSlot("admin-list", "back-slot");
        int prevSlot = cfg.guiSlot("admin-list", "prev-page-slot");
        int nextSlot = cfg.guiSlot("admin-list", "next-page-slot");

        if (slot == backSlot) {
            player.closeInventory();
            return;
        }
        if (slot == prevSlot) {
            // Bounds-checked here, not just left "greyed out but still clickable" - clicking Prev on
            // the first page (or Next on the last) is now a genuine no-op instead of silently
            // reopening the exact same page or relying on AdminGUI's clamp as the only safety net.
            if (holder.getPage() > 0) {
                int target = holder.getPage() - 1;
                reopen(() -> adminGUI.open(player, target));
            }
            return;
        }
        if (slot == nextSlot) {
            if (holder.getPage() < holder.getTotalPages() - 1) {
                int target = holder.getPage() + 1;
                reopen(() -> adminGUI.open(player, target));
            }
            return;
        }

        String context = holder.getContext(slot);
        UUID targetUuid = targetUuidOf(context);
        if (targetUuid == null) return;
        String tagId = context.substring(context.indexOf(':') + 1);
        int page = holder.getPage();

        if (click == ClickType.LEFT) {
            plugin.tagService().approve(player, targetUuid, tagId);
            reopen(() -> adminGUI.open(player, page));
        } else if (click == ClickType.SHIFT_RIGHT) {
            // silent + refunded: request removed, no chat message sent, token given back as a courtesy
            plugin.tagService().rejectSilent(player, targetUuid, tagId, true);
            reopen(() -> adminGUI.open(player, page));
        } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            // silent + NOT refunded: request removed for good, no chat message, no token back
            plugin.tagService().rejectSilent(player, targetUuid, tagId, false);
            reopen(() -> adminGUI.open(player, page));
        } else if (click == ClickType.RIGHT) {
            reopen(() -> adminReasonGUI.open(player, context));
        }
    }

    /**
     * Parses the {@code <uuid>:<tagId>} slot context the admin menus store. Returns null for
     * anything malformed rather than throwing: the context is plugin-written, but a stale holder
     * from a menu drawn before a reload (or a future format change) must degrade to an ignored
     * click, not an ArrayIndexOutOfBounds/IllegalArgumentException surfacing as a console error
     * on every click.
     */
    private static UUID targetUuidOf(String context) {
        if (context == null) return null;
        int separator = context.indexOf(':');
        if (separator <= 0 || separator == context.length() - 1) return null;
        try {
            return UUID.fromString(context.substring(0, separator));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Cleanup view for ANY status of a specific player's tags (see AdminPlayerTagsGUI) - this is
     * what actually uses {@link com.mellishy.customtag.service.TagService#deleteSilent}, since the
     * pending-only queue in handleAdminList can never target an already-APPROVED or REJECTED tag.
     */
    private void handleAdminPlayerTags(Player player, MellishyInventoryHolder holder, int slot, ClickType click) {
        ConfigManager cfg = plugin.config();
        int backSlot = cfg.guiSlot("admin-player-tags", "back-slot");
        int prevSlot = cfg.guiSlot("admin-player-tags", "prev-page-slot");
        int nextSlot = cfg.guiSlot("admin-player-tags", "next-page-slot");
        int page = holder.getPage();

        String targetRaw = holder.getContext(-1);
        if (targetRaw == null) return;
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetRaw);
        } catch (IllegalArgumentException ex) {
            return;
        }

        if (slot == backSlot) {
            player.closeInventory();
            return;
        }
        if (slot == prevSlot) {
            if (page > 0) reopen(() -> adminPlayerTagsGUI.open(player, targetUuid, page - 1));
            return;
        }
        if (slot == nextSlot) {
            if (page + 1 < holder.getTotalPages()) reopen(() -> adminPlayerTagsGUI.open(player, targetUuid, page + 1));
            return;
        }

        String tagId = holder.getContext(slot);
        if (tagId == null) return;

        if (click == ClickType.SHIFT_RIGHT) {
            plugin.tagService().deleteSilent(player, targetUuid, tagId, true);
            reopen(() -> adminPlayerTagsGUI.open(player, targetUuid, page));
        } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            plugin.tagService().deleteSilent(player, targetUuid, tagId, false);
            reopen(() -> adminPlayerTagsGUI.open(player, targetUuid, page));
        }
    }

    private void handleAdminReason(Player player, MellishyInventoryHolder holder, int slot) {
        String target = holder.getContext(-1);
        String action = holder.getContext(slot);
        if (action == null) return;

        if (action.equals("custom")) {
            if (target == null) return;
            player.closeInventory();
            plugin.chatInput().await(player, ChatInputListener.InputType.ADMIN_REASON, target);
            player.sendMessage(ColorUtil.parse(plugin.config().msg("admin-reject-prompt")));
        } else if (action.startsWith("preset:")) {
            UUID targetUuid = targetUuidOf(target);
            if (targetUuid == null) return;
            int index;
            try {
                index = Integer.parseInt(action.substring("preset:".length()));
            } catch (NumberFormatException ex) {
                return;
            }
            var presets = plugin.config().rejectPresets();
            // index >= 0 too: rejectPresets() is re-read live, so a preset list shortened by a
            // /customtag reload while this menu was open must not index out of bounds.
            if (index >= 0 && index < presets.size()) {
                plugin.tagService().reject(player, targetUuid,
                        target.substring(target.indexOf(':') + 1), presets.get(index));
                reopen(() -> adminGUI.open(player));
            }
        }
    }
}
