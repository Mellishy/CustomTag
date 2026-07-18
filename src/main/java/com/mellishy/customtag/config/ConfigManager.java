package com.mellishy.customtag.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thin wrapper around config.yml so nothing in the codebase is hardcoded.
 * Call {@link #reload()} to hot-reload after /customtag reload.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.cfg = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
        warnedMissingSlots.clear();
    }

    public FileConfiguration raw() {
        return cfg;
    }

    public int startingTokens() {
        return cfg.getInt("tokens.starting-amount", 3);
    }

    public int maxTagsPerPlayer() {
        return cfg.getInt("tokens.max-tags-per-player", 10);
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

    public int guiSize(String menu) {
        return cfg.getInt("gui." + menu + ".size", 27);
    }

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
        return cfg.getInt(path, 0);
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

    /** Minimum APPROVED tags required before the random-tag feature can even be turned on. */
    public int randomMinTags() {
        return cfg.getInt("random-tag.min-tags", 2);
    }

    /** From this many approved tags onward, the dedicated subset-picker menu becomes meaningful/unlocked. */
    public int randomSubsetUnlockTags() {
        return cfg.getInt("random-tag.subset-unlock-tags", 4);
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