package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.gui.*;
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
            case ADMIN_LIST -> handleAdminList(player, holder, slot, click);
            case ADMIN_REASON -> handleAdminReason(player, holder, slot);
            case RANDOM_SETTINGS -> handleRandomSettings(player, holder, slot);
            case ADMIN_PLAYER_TAGS -> handleAdminPlayerTags(player, holder, slot, click);
        }
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
            tagListGUI.open(player);
        } else if (slot == createSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (plugin.tagService().canOpenCreateMethod(data)) {
                createMethodGUI.open(player, null, CreateMethodGUI.Origin.MAIN_MENU);
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

        if (slot == backSlot) {
            mainMenuGUI.open(player);
            return;
        }
        if (slot == randomSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (data.approvedTagCount() >= cfg.randomMinTags()) {
                randomSettingsGUI.open(player);
            } else {
                player.sendMessage(ColorUtil.parse(cfg.msg("random-not-enough-tags").replace("{min}", String.valueOf(cfg.randomMinTags()))));
            }
            return;
        }
        if (slot == createSlot) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            if (plugin.tagService().canOpenCreateMethod(data)) {
                createMethodGUI.open(player, null, CreateMethodGUI.Origin.TAG_LIST);
            } else {
                player.closeInventory();
                tagListGUI.open(player); // reopen to show the reason in item lore
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
                if (opt.get().getStatus() == com.mellishy.customtag.data.TagStatus.PENDING) {
                    player.sendMessage(com.mellishy.customtag.util.ColorUtil.parse(cfg.msg("request-pending-block")));
                    return;
                }
                player.closeInventory();
                createMethodGUI.open(player, tagId, CreateMethodGUI.Origin.TAG_LIST);
            }
            case RIGHT -> {
                plugin.tagService().selectTag(player, tagId);
                tagListGUI.open(player);
            }
            case DROP, CONTROL_DROP -> {
                plugin.tagService().deleteTag(player, tagId);
                tagListGUI.open(player);
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
                mainMenuGUI.open(player);
            } else {
                tagListGUI.open(player);
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
            player.sendMessage(com.mellishy.customtag.util.ColorUtil.parse(cfg.msg("chat-input-prompt")));
        }
    }

    private void handleRandomSettings(Player player, MellishyInventoryHolder holder, int slot) {
        ConfigManager cfg = plugin.config();
        int toggleSlot = cfg.guiSlot("random-settings", "toggle-slot");
        int backSlot = cfg.guiSlot("random-settings", "back-slot");

        if (slot == backSlot) {
            tagListGUI.open(player);
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
            randomSettingsGUI.open(player);
            return;
        }

        String tagId = holder.getContext(slot);
        if (tagId == null || tagId.equals("toggle")) return;

        if (data.getRandomTagPool().contains(tagId)) {
            data.getRandomTagPool().remove(tagId);
        } else {
            data.getRandomTagPool().add(tagId);
        }
        plugin.data().save(data);
        randomSettingsGUI.open(player);
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
                adminGUI.open(player, holder.getPage() - 1);
            }
            return;
        }
        if (slot == nextSlot) {
            if (holder.getPage() < holder.getTotalPages() - 1) {
                adminGUI.open(player, holder.getPage() + 1);
            }
            return;
        }

        String context = holder.getContext(slot);
        if (context == null) return;
        String[] parts = context.split(":", 2);
        UUID targetUuid = UUID.fromString(parts[0]);
        String tagId = parts[1];

        if (click == ClickType.LEFT) {
            plugin.tagService().approve(player, targetUuid, tagId);
            adminGUI.open(player, holder.getPage());
        } else if (click == ClickType.SHIFT_RIGHT) {
            // silent + refunded: request removed, no chat message sent, token given back as a courtesy
            plugin.tagService().rejectSilent(player, targetUuid, tagId, true);
            adminGUI.open(player, holder.getPage());
        } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            // silent + NOT refunded: request removed for good, no chat message, no token back
            plugin.tagService().rejectSilent(player, targetUuid, tagId, false);
            adminGUI.open(player, holder.getPage());
        } else if (click == ClickType.RIGHT) {
            player.closeInventory();
            adminReasonGUI.open(player, context);
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
        if (slot == backSlot) {
            player.closeInventory();
            return;
        }

        String targetRaw = holder.getContext(-1);
        String tagId = holder.getContext(slot);
        if (targetRaw == null || tagId == null) return;
        UUID targetUuid = UUID.fromString(targetRaw);

        if (click == ClickType.SHIFT_RIGHT) {
            plugin.tagService().deleteSilent(player, targetUuid, tagId, true);
            adminPlayerTagsGUI.open(player, targetUuid);
        } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            plugin.tagService().deleteSilent(player, targetUuid, tagId, false);
            adminPlayerTagsGUI.open(player, targetUuid);
        }
    }

    private void handleAdminReason(Player player, MellishyInventoryHolder holder, int slot) {
        String target = holder.getContext(-1);
        String action = holder.getContext(slot);
        if (action == null) return;

        if (action.equals("custom")) {
            player.closeInventory();
            plugin.chatInput().await(player, ChatInputListener.InputType.ADMIN_REASON, target);
            player.sendMessage(com.mellishy.customtag.util.ColorUtil.parse(plugin.config().msg("admin-reject-prompt")));
        } else if (action.startsWith("preset:")) {
            int index = Integer.parseInt(action.substring("preset:".length()));
            var presets = plugin.config().rejectPresets();
            if (index < presets.size()) {
                String[] parts = target.split(":", 2);
                plugin.tagService().reject(player, UUID.fromString(parts[0]), parts[1], presets.get(index));
                player.closeInventory();
                adminGUI.open(player);
            }
        }
    }
}
