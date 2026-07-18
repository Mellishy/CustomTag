package com.mellishy.customtag;

import com.mellishy.customtag.command.CustomTagCommand;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.config.GuiStateManager;
import com.mellishy.customtag.cooldown.CooldownManager;
import com.mellishy.customtag.data.DataManager;
import com.mellishy.customtag.listener.BookEditListener;
import com.mellishy.customtag.listener.ChatInputListener;
import com.mellishy.customtag.listener.ChatTagListener;
import com.mellishy.customtag.listener.GuiListener;
import com.mellishy.customtag.listener.PlayerJoinListener;
import com.mellishy.customtag.listener.PlayerPreLoginListener;
import com.mellishy.customtag.listener.PlayerQuitListener;
import com.mellishy.customtag.placeholder.MellishyPlaceholder;
import com.mellishy.customtag.service.TagService;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class MellishyCustomTag extends JavaPlugin {

    private static MellishyCustomTag instance;

    private ConfigManager configManager;
    private GuiStateManager guiStateManager;
    private DataManager dataManager;
    private CooldownManager cooldownManager;
    private ChatInputListener chatInputListener;
    private TagService tagService;
    private BookEditListener bookEditListener;

    /** Non-null only while chat.auto-apply-tag is actually active - see {@link #applyChatAutoApplySetting()}. */
    private ChatTagListener chatTagListener;
    /** Non-null only while the PlaceholderAPI hook is actually registered - see {@link #applyPlaceholderSetting()}. */
    private MellishyPlaceholder placeholderExpansion;

    @Override
    public void onEnable() {
        instance = this;

        this.configManager = new ConfigManager(this);
        this.guiStateManager = new GuiStateManager(this);
        this.dataManager = new DataManager(this, configManager);
        this.dataManager.init();
        this.cooldownManager = new CooldownManager();
        this.chatInputListener = new ChatInputListener(this);
        this.tagService = new TagService(this);

        getServer().getPluginManager().registerEvents(tagService, this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(chatInputListener, this);
        this.bookEditListener = new BookEditListener(this);
        getServer().getPluginManager().registerEvents(bookEditListener, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerPreLoginListener(this), this);

        applyChatAutoApplySetting();

        CustomTagCommand cmd = new CustomTagCommand(this);
        getCommand("customtag").setExecutor(cmd);
        getCommand("customtag").setTabCompleter(cmd);

        applyPlaceholderSetting();

        getLogger().info("CustomTag enabled.");
    }

    /**
     * Registers or unregisters {@link ChatTagListener} to match the CURRENT value of
     * chat.auto-apply-tag - called once from {@link #onEnable()} and again from {@link #reloadDynamicHooks()}
     * so that {@code /customtag reload} can actually flip this setting live instead of only taking
     * effect after a full server restart, which was the previous (silently incomplete) behaviour.
     */
    private void applyChatAutoApplySetting() {
        boolean shouldBeEnabled = configManager.chatAutoApplyEnabled();
        boolean currentlyEnabled = chatTagListener != null;
        if (shouldBeEnabled == currentlyEnabled) return; // already in the right state, nothing to do

        if (shouldBeEnabled) {
            chatTagListener = new ChatTagListener(this);
            getServer().getPluginManager().registerEvents(chatTagListener, this);
            getLogger().info("Automatic chat tag rendering is ENABLED (chat.auto-apply-tag: true in config.yml).");
        } else {
            HandlerList.unregisterAll(chatTagListener);
            chatTagListener = null;
            getLogger().info("Automatic chat tag rendering is DISABLED - use %customtag_tag% in another plugin instead.");
        }
    }

    /**
     * Registers or unregisters the PlaceholderAPI hook to match the CURRENT value of
     * placeholders.enabled - same reasoning as {@link #applyChatAutoApplySetting()}. Safe to call even
     * if PlaceholderAPI isn't installed (register/unregister simply never happen).
     */
    private void applyPlaceholderSetting() {
        boolean placeholderApiPresent = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean shouldBeEnabled = configManager.placeholdersEnabled() && placeholderApiPresent;
        boolean currentlyEnabled = placeholderExpansion != null;
        if (shouldBeEnabled == currentlyEnabled) return;

        if (shouldBeEnabled) {
            placeholderExpansion = new MellishyPlaceholder(this);
            placeholderExpansion.register();
            getLogger().info("PlaceholderAPI hook enabled (%customtag_tag%).");
        } else if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
            getLogger().info("PlaceholderAPI hook disabled.");
        }
    }

    /**
     * Called from {@code /customtag reload} (after the config file itself has been reloaded) so the
     * two settings that used to only be evaluated once at {@link #onEnable()} - chat.auto-apply-tag
     * and placeholders.enabled - actually take effect immediately instead of silently requiring a full
     * server restart while the command claims to have "reloaded" everything.
     */
    public void reloadDynamicHooks() {
        applyChatAutoApplySetting();
        applyPlaceholderSetting();
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            // shutdown() itself now drains pending async saves and performs the final synchronous
            // saveAll() in the correct order - see its javadoc. Do not call saveAll() separately
            // here first; that used to let an older, still in-flight async write land AFTER this
            // "final" save and silently overwrite it with stale data.
            dataManager.shutdown();
        }
        getLogger().info("CustomTag disabled, all data saved.");
        // Bukkit already unregisters every listener owned by this plugin on disable, and
        // PlaceholderAPI unregisters this plugin's expansions automatically too - these two fields
        // are cleared regardless, purely so nothing keeps referencing a listener/expansion tied to a
        // now-dead plugin instance if something else still held a reference to `this`.
        chatTagListener = null;
        placeholderExpansion = null;
        // Clear the static reference so a plugin reload (e.g. /reload or a plugin manager) doesn't
        // pin this whole instance - and therefore its classloader and everything it references
        // (the ioExecutor's threads should already be shut down above, but the object graph itself
        // would otherwise stay reachable via this static field alone) - in memory forever.
        instance = null;
    }

    public static MellishyCustomTag getInstance() {
        return instance;
    }

    public ConfigManager config() {
        return configManager;
    }

    public GuiStateManager guiStates() {
        return guiStateManager;
    }

    public DataManager data() {
        return dataManager;
    }

    public CooldownManager cooldown() {
        return cooldownManager;
    }

    public ChatInputListener chatInput() {
        return chatInputListener;
    }

    public TagService tagService() {
        return tagService;
    }

    public BookEditListener bookEdit() {
        return bookEditListener;
    }
}