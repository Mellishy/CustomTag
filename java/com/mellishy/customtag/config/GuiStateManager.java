package com.mellishy.customtag.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads the 4 standalone, fully-custom GUI theme files that drive every status-colored glass pane
 * in the plugin:
 *
 *   guiNoRequest.yml -> gray  - player has never submitted anything (main menu / tag list background)
 *   guiPending.yml    -> orange - player has a request awaiting review, AND the admin queue background
 *                                 whenever there's at least one pending request to review
 *   guiApproved.yml   -> green  - player's tag is active/approved, AND the admin queue background
 *                                 whenever the queue is empty ("all caught up")
 *   guiReject.yml     -> red    - player's most recent request was rejected
 *
 * Each file is a real file on disk (not a section of config.yml) exactly as requested, so a server
 * owner can hand any one of them to someone else to re-skin without touching the main config, and so
 * every material/glow/name/lore for a given state lives in one obvious place.
 */
public class GuiStateManager {

    private final JavaPlugin plugin;

    private GuiStateTheme noRequest;
    private GuiStateTheme pending;
    private GuiStateTheme approved;
    private GuiStateTheme rejected;

    public GuiStateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        noRequest = load("guiNoRequest.yml", Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE,
                Material.LIGHT_GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);
        pending = load("guiPending.yml", Material.ORANGE_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE);
        approved = load("guiApproved.yml", Material.GREEN_STAINED_GLASS_PANE, Material.GREEN_STAINED_GLASS_PANE,
                Material.GREEN_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE);
        rejected = load("guiReject.yml", Material.RED_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
                Material.RED_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE);
    }

    private GuiStateTheme load(String fileName, Material border, Material side, Material filler, Material fillerAlt) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException ex) {
                // no bundled default for this file name - fine, defaults below still apply
            }
        }
        YamlConfiguration yml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        return GuiStateTheme.fromYaml(yml, border, side, filler, fillerAlt);
    }

    public GuiStateTheme noRequest() {
        return noRequest;
    }

    public GuiStateTheme pending() {
        return pending;
    }

    public GuiStateTheme approved() {
        return approved;
    }

    public GuiStateTheme rejected() {
        return rejected;
    }
}
