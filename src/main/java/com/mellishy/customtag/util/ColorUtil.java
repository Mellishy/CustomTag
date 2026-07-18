package com.mellishy.customtag.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Unified color parser.
 * Accepts, in the SAME string, any mix of:
 *   - legacy codes:      &c, &l, &r ...
 *   - legacy hex:        &#RRGGBB
 *   - full MiniMessage:  <red>, <#ff00aa>, <gradient:#ff0000:#0000ff>text</gradient>, <bold>, etc.
 *
 * Internally everything is normalised into MiniMessage before being parsed once,
 * so gradients/hex/legacy can all be combined freely by the player.
 */
public final class ColorUtil {

    private ColorUtil() {}

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static final Map<Character, String> LEGACY_TO_TAG = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"), Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    /**
     * Normalises legacy '&' codes (including &#hex) into MiniMessage tags,
     * leaving any already-present MiniMessage tags untouched.
     */
    public static String toMiniMessage(String input) {
        if (input == null) return "";
        String result = HEX_PATTERN.matcher(input).replaceAll(mr -> "<#" + mr.group(1) + ">");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c == '&' && i + 1 < result.length()) {
                char next = Character.toLowerCase(result.charAt(i + 1));
                if (LEGACY_TO_TAG.containsKey(next)) {
                    sb.append(LEGACY_TO_TAG.get(next));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Parses a raw player-provided string (legacy + hex + minimessage) into an Adventure Component. */
    public static Component parse(String raw) {
        String normalised = toMiniMessage(raw);
        try {
            return MiniMessage.miniMessage().deserialize(normalised);
        } catch (Exception ex) {
            // fall back to plain legacy parsing so a malformed MiniMessage tag never breaks the whole tag
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('&', '\u00A7'));
        }
    }

    /**
     * Formatting-only tag set (colors, bold/italic/underline/etc, gradients, rainbow, font, reset) -
     * deliberately excludes every INTERACTIVE tag MiniMessage supports (click_event/click, hover_event/
     * hover, insertion, keybind, translatable, selector, score, nbt, ...).
     */
    private static final TagResolver SAFE_TAGS = TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
            StandardTags.rainbow(), StandardTags.font(), StandardTags.reset()
    );
    private static final MiniMessage SAFE_MINI_MESSAGE = MiniMessage.builder().tags(SAFE_TAGS).build();

    /**
     * Same as {@link #parse(String)}, but for rendering a PLAYER-SUBMITTED tag anywhere it will be
     * shown to an audience OTHER than the player who wrote it (live chat via {@code ChatTagListener},
     * PlaceholderAPI output that other plugins/scoreboards may display, etc).
     *
     * A tag is free-form text an admin approves after reading only its plain, stripped content (see
     * {@link #stripToPlain}) - the admin never sees it live-rendered with full MiniMessage tags during
     * review. If {@link #parse} were used here too, an approved tag could smuggle an interactive
     * {@code <click:open_url:'...'>} or {@code <hover:...>} payload into every chat message that
     * player sends, invisible to the admin who approved it. This method strips exactly that class of
     * tag while still allowing every purely cosmetic one (colors, gradients, bold, etc), which is all
     * a "tag" is meant to be. Legacy '&' codes and '&#hex' are unaffected - only MiniMessage-syntax
     * interactive tags are ever disabled, and they simply render as their own plain-text fallback
     * (MiniMessage's standard behaviour for a tag it wasn't given a resolver for) instead of breaking
     * the whole tag.
     */
    public static Component parseForOthers(String raw) {
        String normalised = toMiniMessage(raw);
        try {
            return SAFE_MINI_MESSAGE.deserialize(normalised);
        } catch (Exception ex) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('&', '\u00A7'));
        }
    }

    /** Convenience for plugin messages that only use simple '&' legacy codes. */
    public static Component parseSimple(String raw) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw == null ? "" : raw);
    }

    /** Renders a component down to a legacy-coded plain string, e.g. for logs or PlaceholderAPI output. */
    public static String toLegacyString(Component component) {
        return LegacyComponentSerializer.legacyAmpersand().serialize(component);
    }

    /**
     * Strips every color/format code (legacy, hex, MiniMessage) from a raw, player-submitted string
     * and returns the plain human-readable text underneath. Used anywhere a human needs to read
     * exactly WHAT a player wrote (e.g. the admin review menu) without the tag rendering as an
     * actual colored tag - showing an admin a live-rendered tag instead of plain text is what makes
     * the request line look broken/unreadable, so this is used there instead of {@link #parse}.
     */
    public static String stripToPlain(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return PlainTextComponentSerializer.plainText().serialize(parse(raw));
    }
}