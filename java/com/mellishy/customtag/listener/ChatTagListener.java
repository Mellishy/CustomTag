package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.data.DataManager;
import com.mellishy.customtag.util.ColorUtil;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Renders the player's custom tag directly into their chat messages.
 *
 * Two tag-selection modes:
 *   normal        -> always renders the single tag the player picked as "active" (unchanged).
 *   random mode   -> (see Random Tag Settings in the tag list menu) picks a DIFFERENT random tag
 *                     from the player's eligible pool for every single message, e.g.
 *                      mellishy [pro]: salam
 *                      mellishy [hunter]: khobi?
 *                      mellishy [god]: xd
 *                     The eligible pool is whatever the player selected in Random Tag Settings, or
 *                     every approved tag if they never picked a subset - see
 *                     {@link com.mellishy.customtag.data.PlayerData#resolveRandomPool()}. Random mode is skipped entirely (falls
 *                     back to the active tag) if the player somehow no longer has enough approved
 *                     tags for it, so toggling it off/losing tags can never break chat.
 *
 * This is what actually fixes the original bug: selecting a tag updated the player's data, but
 * nothing in the plugin ever put that tag next to the player's name in chat - it only exposed a
 * PlaceholderAPI value and silently assumed a separate chat plugin was wired up to use it, which
 * most servers never do. This listener makes the plugin work correctly out of the box.
 *
 * Two supported modes (see config.yml -> chat.auto-apply-tag):
 *   true  -> this listener is registered and every message is rendered with the tag automatically,
 *            no other plugin or server-side setup required.
 *   false -> this listener is never registered at all, chat is left completely untouched, and you
 *            wire the tag into your own chat/tab/nametag plugin yourself using %customtag_tag%
 *            (see MellishyPlaceholder).
 */
public class ChatTagListener implements Listener {

    private final MellishyCustomTag plugin;

    public ChatTagListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        event.renderer(ChatRenderer.viewerUnaware(this::render));
    }

    /**
     * This renderer runs on Paper's async chat thread, NOT the main thread - so it must never touch
     * a live {@link com.mellishy.customtag.data.PlayerData} directly (see the THREAD SAFETY javadoc
     * on {@link DataManager#renderSnapshot}). It only ever reads the immutable
     * {@link DataManager.RenderSnapshot}, which is exactly what makes this safe.
     */
    private Component render(Player source, Component displayName, Component message) {
        DataManager.RenderSnapshot snapshot = plugin.data().renderSnapshot(source.getUniqueId());

        // parseForOthers(), not parse(): this tag renders in EVERY other player's chat, and an admin
        // only ever reviewed its plain, stripped text (see ColorUtil#stripToPlain in AdminGUI) - never
        // a live-rendered preview with full MiniMessage tags - so interactive tags (click/hover/...)
        // must never reach this audience even on an already-approved tag.
        Component tagComponent = resolveTagText(snapshot)
                .map(ColorUtil::parseForOthers)
                .orElseGet(() -> ColorUtil.parseSimple(plugin.config().placeholderEmptyValue()));

        return buildFromTemplate(plugin.config().chatFormat(), tagComponent, displayName, message);
    }

    private Optional<String> resolveTagText(DataManager.RenderSnapshot snapshot) {
        if (snapshot.randomEnabled() && !snapshot.randomPoolRaw().isEmpty()) {
            List<String> pool = snapshot.randomPoolRaw();
            return Optional.of(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        return Optional.ofNullable(snapshot.activeTagRaw());
    }

    /**
     * Resolves {tag}/{player}/{message} as real MiniMessage placeholders (Tag.inserting) in a
     * SINGLE deserialize pass, instead of splitting the template into raw-text fragments and
     * parsing each one separately.
     *
     * The old fragment-splitting approach is what caused tags to render literally in chat (e.g.
     * "<white>{player}</white>" showing up as raw "<white> PlayerName </wihte>:" text): any
     * MiniMessage tag that OPENS in one fragment and CLOSES in another (very common, since
     * templates naturally wrap a placeholder like "<white>{player}</white>") is invalid
     * MiniMessage on its own once split apart, so each fragment failed to parse and silently fell
     * back to being printed as literal text. Resolving placeholders in one pass means tags can
     * safely span across them, exactly like a normal MiniMessage string.
     */
    private Component buildFromTemplate(String format, Component tag, Component player, Component message) {
        String miniMessageTemplate = ColorUtil.toMiniMessage(format)
                .replace("{tag}", "<ct_tag>")
                .replace("{player}", "<ct_player>")
                .replace("{message}", "<ct_message>");

        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("ct_tag", tag),
                Placeholder.component("ct_player", player),
                Placeholder.component("ct_message", message)
        );

        try {
            return MiniMessage.miniMessage().deserialize(miniMessageTemplate, resolver);
        } catch (Exception ex) {
            // malformed custom format - fail safe with a plain, unstyled but still correct layout
            // rather than showing garbled/leaked tag syntax in chat
            return Component.empty().append(tag).append(Component.space()).append(player)
                    .append(Component.text(": ")).append(message);
        }
    }
}