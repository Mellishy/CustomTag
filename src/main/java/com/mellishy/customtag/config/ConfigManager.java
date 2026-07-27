package com.mellishy.customtag.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thin wrapper around config.yml so nothing in the codebase is hardcoded.
 * Call {@link #reload()} to hot-reload after /customtag reload.
 *
 * THREAD SAFETY: almost every getter here is called on the main thread, but two are not -
 * {@link com.mellishy.customtag.listener.ChatTagListener} reads {@link #chatFormat()} on Paper's
 * async chat thread and {@link com.mellishy.customtag.placeholder.MellishyPlaceholder} reads
 * {@link #placeholderEmptyValue()} on whatever thread the requesting plugin uses. Reading a
 * loaded {@link FileConfiguration} concurrently is fine; swapping the reference out from under
 * those readers on reload is not, which is why the field is volatile.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    /** volatile: replaced by {@link #reload()} on the main thread, read from async threads - see class javadoc. */
    private volatile FileConfiguration cfg;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.cfg = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
        // re-arm the one-time warnings: the reloaded file may have brand-new problems, and staying
        // quiet about them because the same key was already reported hours ago helps nobody
        warnedMissingSlots.clear();
        warnedBadSizes.clear();
        warnedOutOfRangeSlots.clear();
    }

    public FileConfiguration raw() {
        return cfg;
    }

    public int startingTokens() {
        return cfg.getInt("tokens.starting-amount", 3);
    }

    /**
     * BUGFIX: no lower bound used to be enforced here. A negative value (e.g. an admin typo like
     * "-10" instead of "10") still functionally blocked every new tag (since activeTagCount() can
     * never be negative), but produced a nonsensical "&7X&8/&7-10 slots used" readout in every menu
     * (MainMenuGUI, TagListGUI) instead of a sane, always-non-negative limit.
     */
    public int maxTagsPerPlayer() {
        return Math.max(0, cfg.getInt("tokens.max-tags-per-player", 10));
    }

    /** Max VISIBLE (plain, colors stripped) length allowed for a submitted tag - see tokens.max-tag-length. */
    public int maxTagLength() {
        return Math.max(1, cfg.getInt("tokens.max-tag-length", 32));
    }

    /** How many REJECTED tags are kept per player before the oldest are pruned - 0 = unlimited. See tokens.max-rejected-history. */
    public int maxRejectedHistory() {
        return Math.max(0, cfg.getInt("tokens.max-rejected-history", 5));
    }

    public int cancelCooldownSeconds() {
        return cfg.getInt("cooldown.cancel-cooldown-seconds", 300);
    }

    public String storageFolder() {
        return cfg.getString("storage.folder", "playerdata");
    }

    // ----- storage backend: yaml (default) | mysql | mongodb -----

    public String storageType() {
        return cfg.getString("storage.type", "yaml");
    }

    public String mysqlHost() {
        return cfg.getString("storage.mysql.host", "localhost");
    }

    public int mysqlPort() {
        return cfg.getInt("storage.mysql.port", 3306);
    }

    public String mysqlDatabase() {
        return cfg.getString("storage.mysql.database", "customtag");
    }

    public String mysqlUsername() {
        return cfg.getString("storage.mysql.username", "root");
    }

    public String mysqlPassword() {
        return cfg.getString("storage.mysql.password", "");
    }

    public boolean mysqlUseSsl() {
        return cfg.getBoolean("storage.mysql.use-ssl", false);
    }

    public String mysqlTablePrefix() {
        return cfg.getString("storage.mysql.table-prefix", "ct_");
    }

    /** HikariCP maximum pool size - see storage.mysql.pool-size in config.yml. */
    public int mysqlPoolSize() {
        return Math.max(1, cfg.getInt("storage.mysql.pool-size", 10));
    }

    // ----- opt-in cache eviction for very large player bases (see DataManager#scheduleEviction) -----

    public boolean cacheEvictionEnabled() {
        return cfg.getBoolean("storage.cache-eviction.enabled", false);
    }

    public int cacheEvictionDelaySeconds() {
        return Math.max(10, cfg.getInt("storage.cache-eviction.delay-seconds", 300));
    }

    public String mongoConnectionString() {
        return cfg.getString("storage.mongodb.connection-string", "mongodb://localhost:27017");
    }

    public String mongoDatabase() {
        return cfg.getString("storage.mongodb.database", "customtag");
    }

    public String mongoCollection() {
        return cfg.getString("storage.mongodb.collection", "players");
    }

    public boolean placeholdersEnabled() {
        return cfg.getBoolean("placeholders.enabled", true);
    }

    public String placeholderEmptyValue() {
        return cfg.getString("placeholders.empty-value", "");
    }

    // ----- chat rendering -----

    /**
     * true  -> the plugin renders the player's active tag into chat itself (ChatTagListener), no
     *          other setup needed.
     * false -> chat is left untouched; use %customtag_tag% in your own chat/tab/nametag plugin.
     */
    public boolean chatAutoApplyEnabled() {
        return cfg.getBoolean("chat.auto-apply-tag", true);
    }

    /** Template used by ChatTagListener when chat.auto-apply-tag is true. Supports {tag} {player} {message}. */
    public String chatFormat() {
        return cfg.getString("chat.format", "<white>{player}</white> {tag}&7: &f{message}");
    }

    // ----- customizable base64 head icons (leave a value blank to use the default material instead) -----

    public String iconBase64(String path) {
        return cfg.getString("gui.icons." + path, "");
    }

    public String msg(String path) {
        String prefix = cfg.getString("messages.prefix", "");
        String m = cfg.getString("messages." + path, path);
        return prefix + m;
    }

    public String rawMsg(String path) {
        return cfg.getString("messages." + path, path);
    }

    public List<String> rejectPresets() {
        return cfg.getStringList("messages.reject-presets");
    }

    public List<String> profileLore() {
        return cfg.getStringList("lore.profile");
    }

    public String loreValue(String path) {
        return cfg.getString("lore." + path, "");
    }

    public String bookHelpPage() {
        return cfg.getString("book.help-page", "");
    }

    public String bookTemplatePage() {
        return cfg.getString("book.template-page", "&a&l[YourTag]");
    }

    // ----- GUI settings -----
    public String guiTitle(String menu) {
        return cfg.getString("gui." + menu + ".title", menu);
    }

    /**
     * Bukkit only accepts a chest size that is a multiple of 9 between 9 and 54 - anything else
     * makes {@code createInventory} throw an IllegalArgumentException. This value came straight from
     * config.yml unchecked, so a single mistyped {@code size: 50} took that whole menu out with a
     * stack trace every time anyone tried to open it.
     *
     * Rounded UP to the nearest legal size, with a one-time warning, so the mistake degrades into
     * "a slightly roomier menu than I asked for" rather than a dead feature. Rounding up rather than
     * down also keeps the menu's configured slots in range wherever possible.
     */
    public int guiSize(String menu) {
        int raw = cfg.getInt("gui." + menu + ".size", 27);
        int clamped = Math.clamp((raw + 8) / 9 * 9, 9, 54);
        if (clamped != raw && warnedBadSizes.add(menu)) {
            plugin.getLogger().warning("[CustomTag] gui." + menu + ".size is " + raw
                    + ", which is not a usable chest size - using " + clamped + " instead. Valid sizes are "
                    + "multiples of 9 from 9 to 54.");
        }
        return clamped;
    }

    private final Set<String> warnedBadSizes = new HashSet<>();
    private final Set<String> warnedOutOfRangeSlots = new HashSet<>();

    /**
     * Missing slot keys used to silently fall back to slot 0 with zero indication anything was
     * wrong - if a server owner accidentally deleted or mistyped a slot key in config.yml, buttons
     * would just silently pile up on top of each other in the corner with no error anywhere. Now a
     * missing key logs a clear one-time warning per menu/slot pair (so a busy console isn't spammed
     * every time the GUI opens) and still falls back to 0 so the menu never hard-crashes.
     */
    private final Set<String> warnedMissingSlots = new HashSet<>();

    public int guiSlot(String menu, String slot) {
        String path = "gui." + menu + "." + slot;
        if (!cfg.isSet(path) && warnedMissingSlots.add(path)) {
            plugin.getLogger().warning("[CustomTag] Missing config key '" + path
                    + "' - falling back to slot 0. Check your config.yml for a deleted or mistyped slot entry.");
        }
        int configured = cfg.getInt(path, 0);
        // Inventory#setItem throws for a slot outside the inventory, and the shipped defaults are
        // written for the default size (e.g. back-slot: 49) - so shrinking one menu's `size` without
        // also moving every button in it used to crash that menu on open. Fold it back into range
        // instead: a button in an odd place is a cosmetic problem, an unopenable menu is not.
        int size = guiSize(menu);
        if (configured < 0 || configured >= size) {
            int corrected = Math.clamp(configured, 0, size - 1);
            if (warnedOutOfRangeSlots.add(path)) {
                plugin.getLogger().warning("[CustomTag] " + path + " is slot " + configured
                        + " but gui." + menu + ".size is only " + size + " - using slot " + corrected
                        + " instead. Lower the slot numbers in this menu or raise its size.");
            }
            return corrected;
        }
        return configured;
    }

    // ----- theme (nothing here is hardcoded - any Material name works, blank/invalid falls back safely) -----

    public String themeBorderMaterial() {
        return cfg.getString("gui.theme.border-material", "BLACK_STAINED_GLASS_PANE");
    }

    public String themeSideMaterial() {
        return cfg.getString("gui.theme.side-material", "GRAY_STAINED_GLASS_PANE");
    }

    public String themeFillerMaterial() {
        return cfg.getString("gui.theme.filler-material", "LIGHT_GRAY_STAINED_GLASS_PANE");
    }

    /** Second color used to checkerboard interior filler slots instead of one flat color everywhere. */
    public String themeFillerAltMaterial() {
        return cfg.getString("gui.theme.filler-material-alt", "WHITE_STAINED_GLASS_PANE");
    }

    /** Per-menu override, falling back to a supplied default (typically one of the theme.* values above). */
    public String guiMaterial(String menu, String key, String fallback) {
        return cfg.getString("gui." + menu + "." + key, fallback);
    }

    public String adminDateFormat() {
        return cfg.getString("messages.admin-date-format", "yyyy-MM-dd HH:mm");
    }

    // ----- random tag rotation -----

    /**
     * Minimum APPROVED tags required before the random-tag feature can even be turned on. Floored
     * at 0 for the same reason as {@link #maxTagsPerPlayer()} - a negative value here would still
     * behave like "always allowed" (any tag count is >= a negative number) but would print a
     * confusing "&7Need at least &f-3&7 approved tags" message to players instead of a sane one.
     */
    public int randomMinTags() {
        return Math.max(0, cfg.getInt("random-tag.min-tags", 2));
    }

    /** From this many approved tags onward, the dedicated subset-picker menu becomes meaningful/unlocked. */
    public int randomSubsetUnlockTags() {
        return Math.max(0, cfg.getInt("random-tag.subset-unlock-tags", 4));
    }

    // ----- chat preview (see ChatInputListener / preview confirmation flow) -----

    public boolean previewEnabled() {
        return cfg.getBoolean("preview.enabled", true);
    }

    /** How long (seconds) a shown-but-unconfirmed preview stays valid before it silently expires. */
    public int previewExpirySeconds() {
        return cfg.getInt("preview.expiry-seconds", 120);
    }
}