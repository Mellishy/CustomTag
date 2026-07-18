package com.mellishy.customtag.placeholder;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.data.DataManager;
import com.mellishy.customtag.util.ColorUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Exposes:
 *   %customtag_tag%          -> the player's active custom tag, fully colored (legacy-serialised)
 *   %customtag_tag_raw%      -> the raw, unparsed text the player submitted
 *   %customtag_tokens%       -> remaining tokens
 *   %customtag_tagcount%     -> how many tags (any status) the player owns
 *
 * When chat.auto-apply-tag is true in config.yml, the plugin already renders the tag into chat
 * itself (see ChatTagListener) and you don't need this at all. Turn that setting off and use
 * %customtag_tag% here if you'd rather feed the tag into your own chat/tab/nametag plugin instead.
 */
public class MellishyPlaceholder extends PlaceholderExpansion {

    private final MellishyCustomTag plugin;

    public MellishyPlaceholder(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "customtag";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "Mellishy";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /**
     * PlaceholderAPI does not guarantee onRequest() is called on the main thread - some hooking
     * plugins (async tab lists, async scoreboards, etc.) call it off-thread. This must therefore
     * only ever read the thread-safe {@link DataManager.RenderSnapshot}, never the live,
     * unsynchronized {@link com.mellishy.customtag.data.PlayerData} object - see the THREAD SAFETY
     * javadoc on {@link DataManager#renderSnapshot} for why.
     */
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        DataManager.RenderSnapshot snapshot = plugin.data().renderSnapshot(player.getUniqueId());

        return switch (params.toLowerCase()) {
            // parseForOthers(), not parse(): this placeholder is meant to be fed into other plugins'
            // chat/tab/nametag/scoreboard output (see the class javadoc) - the same untrusted-to-others
            // audience as ChatTagListener, so it must never carry an interactive click/hover tag either.
            case "tag" -> snapshot.activeTagRaw() != null
                    ? ColorUtil.toLegacyString(ColorUtil.parseForOthers(snapshot.activeTagRaw()))
                    : plugin.config().placeholderEmptyValue();
            case "tag_raw" -> snapshot.activeTagRaw() != null ? snapshot.activeTagRaw() : plugin.config().placeholderEmptyValue();
            case "tokens" -> String.valueOf(snapshot.tokens());
            case "tagcount" -> String.valueOf(snapshot.tagCount());
            default -> null;
        };
    }
}