package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.event.AdminRejectEvent;
import com.mellishy.customtag.event.TagSubmitEvent;
import com.mellishy.customtag.util.ColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the NEXT chat message from a player when they're in "input mode":
 *  - creating/editing a tag via the chat method
 *  - typing a custom admin rejection reason
 *
 * While a player is in input mode their chat message is intercepted (not broadcast).
 *
 * ---- Live preview (see preview.enabled in config.yml) ----
 * For CREATE_TAG / EDIT_TAG, the typed text is no longer submitted straight away. Instead the
 * player is shown exactly how it will look rendered in chat next to their own name - a real
 * preview, not a description of one - followed by a clickable "(Click to create this)" line. Only
 * clicking that line actually submits the request for admin review; nothing is sent to the queue
 * from typing alone. This lets a player retype/tweak their color and text as many times as they
 * like (each new message simply replaces the previous unconfirmed preview) before ever spending
 * their token/creating a real request.
 */
public class ChatInputListener implements Listener {

    public enum InputType { CREATE_TAG, EDIT_TAG, ADMIN_REASON }

    public record PendingInput(InputType type, String context) {}

    /** An unconfirmed preview waiting for the player to click "(Click to create this)". */
    public record PendingPreview(InputType type, String editingTagId, String rawText, String reservationId, long shownAt) {}

    private final MellishyCustomTag plugin;
    private final Map<UUID, PendingInput> awaiting = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPreview> previews = new ConcurrentHashMap<>();

    public ChatInputListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    public void await(Player player, InputType type, String context) {
        awaiting.put(player.getUniqueId(), new PendingInput(type, context));
    }

    public void cancel(Player player) {
        awaiting.remove(player.getUniqueId());
        previews.remove(player.getUniqueId());
    }

    public boolean isAwaiting(Player player) {
        return awaiting.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = awaiting.get(player.getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // run back on the main thread since we touch inventories/config
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(player, pending, message));
    }

    private void handle(Player player, PendingInput pending, String message) {
        awaiting.remove(player.getUniqueId());
        ConfigManager cfg = plugin.config();

        if (message.equalsIgnoreCase("cancel")) {
            previews.remove(player.getUniqueId());
            if (pending.type() == InputType.CREATE_TAG) {
                plugin.tagService().cancelReservation(player); // refund the token reserved for this attempt
            }
            player.sendMessage(ColorUtil.parse(cfg.msg("chat-input-cancelled")));
            return;
        }

        switch (pending.type()) {
            case CREATE_TAG, EDIT_TAG -> {
                String editingId = pending.type() == InputType.EDIT_TAG ? pending.context() : null;
                String reservationId = pending.type() == InputType.CREATE_TAG ? pending.context() : null;

                // BUGFIX: there was previously no length limit at all on player-submitted tag text -
                // an oversized tag would sail straight through preview/submission and, once approved,
                // get rendered into EVERY other player's chat by ChatTagListener on every message.
                // Checked against the PLAIN (color/format codes stripped) length so decorative codes
                // like <gradient:...> or &c don't unfairly eat into the player's visible budget.
                // Re-typing after this message keeps the player in chat-input mode (see below), so
                // they can simply try again with shorter text - no token is spent or lost here.
                int plainLength = ColorUtil.stripToPlain(message).length();
                int maxLength = cfg.maxTagLength();
                if (plainLength > maxLength) {
                    awaiting.put(player.getUniqueId(), pending);
                    player.sendMessage(ColorUtil.parse(cfg.msg("tag-too-long")
                            .replace("{length}", String.valueOf(plainLength))
                            .replace("{max}", String.valueOf(maxLength))));
                    return;
                }

                if (cfg.previewEnabled()) {
                    showPreview(player, pending.type(), editingId, message, reservationId);
                    // stay in chat-input mode: the player can keep retyping to tweak their color/text
                    // as many times as they like, each message simply replacing the previous preview,
                    // until they either click "(Click to create this)" or type cancel
                    awaiting.put(player.getUniqueId(), pending);
                } else {
                    submitDirectly(player, editingId, message, reservationId);
                }
            }
            case ADMIN_REASON -> plugin.getServer().getPluginManager()
                    .callEvent(new AdminRejectEvent(player, pending.context(), message));
        }
    }

    // ---------- preview ----------

    private void showPreview(Player player, InputType type, String editingTagId, String rawText, String reservationId) {
        renderPreview(player, type, editingTagId, rawText, reservationId, false);
    }

    /**
     * Same live preview as the chat method, but triggered from {@link com.mellishy.customtag.listener.BookEditListener} once a
     * creation/edit book is closed. This is what makes the preview step apply equally to BOTH
     * creation methods instead of only chat - previously the book method skipped straight to
     * submitting, with no chance to see or reconsider the tag first.
     *
     * Unlike the chat flow, the player is NOT put into {@link #awaiting} here (their chat isn't
     * being intercepted - they'd retype by requesting a new book instead), but the preview is
     * otherwise identical: same confirm command, same expiry, and now also a clickable cancel line.
     */
    public void showBookPreview(Player player, InputType type, String editingTagId, String rawText, String reservationId) {
        renderPreview(player, type, editingTagId, rawText, reservationId, true);
    }

    private void renderPreview(Player player, InputType type, String editingTagId, String rawText, String reservationId, boolean fromBook) {
        ConfigManager cfg = plugin.config();
        previews.put(player.getUniqueId(), new PendingPreview(type, editingTagId, rawText, reservationId, System.currentTimeMillis()));

        Component tagComponent = ColorUtil.parse(rawText);
        Component playerName = Component.text(player.getName());

        Component headerLine = ColorUtil.parse(cfg.rawMsg("preview-header"))
                .replaceText(b -> b.matchLiteral("{player}").replacement(playerName))
                .replaceText(b -> b.matchLiteral("{tag}").replacement(tagComponent));
        Component tagOnlyLine = ColorUtil.parse(cfg.rawMsg("preview-tag-line"))
                .replaceText(b -> b.matchLiteral("{tag}").replacement(tagComponent));
        Component footerLine = ColorUtil.parse(cfg.rawMsg(fromBook ? "preview-footer-book" : "preview-footer"));

        Component confirmLine = ColorUtil.parse(cfg.rawMsg("preview-confirm"))
                .clickEvent(ClickEvent.runCommand("/customtag confirmcreate"))
                .hoverEvent(HoverEvent.showText(ColorUtil.parse(cfg.rawMsg("preview-hover"))));
        Component cancelLine = ColorUtil.parse(cfg.rawMsg("preview-cancel"))
                .clickEvent(ClickEvent.runCommand("/customtag cancelcreate"))
                .hoverEvent(HoverEvent.showText(ColorUtil.parse(cfg.rawMsg("preview-cancel-hover"))));

        player.sendMessage(headerLine);
        player.sendMessage(tagOnlyLine);
        player.sendMessage(footerLine);
        player.sendMessage(confirmLine);
        player.sendMessage(cancelLine);
    }

    /**
     * Called from /customtag cancelcreate (the click target of the "(Click to cancel)" preview
     * line). Discards the pending preview and, for a brand-new tag, refunds the reservation token -
     * exactly the same outcome as typing "cancel" in the chat flow, just reachable from a book
     * preview too where there's no chat prompt to type into.
     */
    public void cancelPreview(Player player) {
        ConfigManager cfg = plugin.config();
        PendingPreview preview = previews.remove(player.getUniqueId());
        awaiting.remove(player.getUniqueId());
        if (preview == null) {
            player.sendMessage(ColorUtil.parse(cfg.msg("preview-none-pending")));
            return;
        }
        if (preview.type() == InputType.CREATE_TAG) {
            plugin.tagService().cancelReservation(player); // refund the token reserved for this attempt
        }
        player.sendMessage(ColorUtil.parse(cfg.msg("chat-input-cancelled")));
    }

    /**
     * Called from /customtag confirmcreate (the click target of the "(Click to create this)" line).
     *
     * NOTE on expiry: if the preview is too old, this returns early WITHOUT touching
     * {@link #awaiting} or refunding the reservation - both are deliberately left exactly as they
     * were. The player is still in chat-input mode (see {@link #handle}), so simply retyping in
     * chat shows them a brand-new, fresh preview using the same still-reserved token; nothing is
     * lost and no extra token is charged. The reservation itself is only ever cleared by an explicit
     * cancel (typing "cancel" / clicking cancel) or by disconnecting (see
     * {@link com.mellishy.customtag.service.TagService#handleDisconnect}) - expiry of the PREVIEW is
     * intentionally a much softer, resumable state than losing the reservation entirely.
     */
    public void confirmPreview(Player player) {
        ConfigManager cfg = plugin.config();
        PendingPreview preview = previews.remove(player.getUniqueId());
        if (preview == null) {
            player.sendMessage(ColorUtil.parse(cfg.msg("preview-none-pending")));
            return;
        }
        long ageSeconds = (System.currentTimeMillis() - preview.shownAt()) / 1000L;
        if (ageSeconds > cfg.previewExpirySeconds()) {
            player.sendMessage(ColorUtil.parse(cfg.msg("preview-expired")));
            return;
        }
        submitDirectly(player, preview.editingTagId(), preview.rawText(), preview.reservationId());
        awaiting.remove(player.getUniqueId()); // creation finished - stop intercepting this player's chat
    }

    private void submitDirectly(Player player, String editingTagId, String rawText, String reservationId) {
        plugin.getServer().getPluginManager()
                .callEvent(new TagSubmitEvent(player, editingTagId, rawText, reservationId));
    }
}