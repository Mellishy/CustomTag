package com.mellishy.customtag.config;

import com.mellishy.customtag.gui.GuiFrame;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One "status theme": the glass colors (and optional glow) used to represent a single tag/request
 * state - no request yet, pending, approved, rejected - consistently across both the player menus
 * and the admin queue background. Loaded from a standalone, fully custom yml file (see
 * {@link GuiStateManager}) so a server owner can re-theme every state without touching any code.
 */
public class GuiStateTheme {

    private final Material border;
    private final Material side;
    private final Material filler;
    private final Material fillerAlt;
    private final boolean glow;

    private GuiStateTheme(Material border, Material side, Material filler, Material fillerAlt, boolean glow) {
        this.border = border;
        this.side = side;
        this.filler = filler;
        this.fillerAlt = fillerAlt;
        this.glow = glow;
    }

    public static GuiStateTheme fromYaml(YamlConfiguration yml, Material fallbackBorder, Material fallbackSide,
                                          Material fallbackFiller, Material fallbackFillerAlt) {
        Material border = GuiFrame.materialOrFallback(yml.getString("theme.border-material"), fallbackBorder);
        Material side = GuiFrame.materialOrFallback(yml.getString("theme.side-material"), fallbackSide);
        Material filler = GuiFrame.materialOrFallback(yml.getString("theme.filler-material"), fallbackFiller);
        Material fillerAlt = GuiFrame.materialOrFallback(yml.getString("theme.filler-material-alt"), fallbackFillerAlt);
        boolean glow = yml.getBoolean("theme.glow", false);
        return new GuiStateTheme(border, side, filler, fillerAlt, glow);
    }

    public Material border() {
        return border;
    }

    public Material side() {
        return side;
    }

    public Material filler() {
        return filler;
    }

    public Material fillerAlt() {
        return fillerAlt;
    }

    public boolean glow() {
        return glow;
    }
}
