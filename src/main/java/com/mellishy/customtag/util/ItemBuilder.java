package com.mellishy.customtag.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small fluent builder so GUI classes stay readable.
 * Nothing here is hardcoded content-wise - all text comes from config via ColorUtil at call sites.
 */
public class ItemBuilder {

    private final ItemStack stack;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.stack = new ItemStack(material);
        this.meta = stack.getItemMeta();
    }

    public static ItemBuilder playerHead(UUID owner) {
        ItemBuilder b = new ItemBuilder(Material.PLAYER_HEAD);
        if (b.meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(owner));
        }
        return b;
    }

    /**
     * Builds a player-head item wearing a custom skin from a raw "textures" base64 value
     * (the kind of string sites like minecraft-heads.com give you). Used for the customizable
     * create/list button icons in config.yml (gui.icons.*). Falls back gracefully to a plain
     * player head if the base64 is blank/invalid, so a bad config value can never break a GUI.
     *
     * Uses Paper's official {@link com.destroystokyo.paper.profile.PlayerProfile}/{@link ProfileProperty}
     * API (via {@code Bukkit.createProfile} + {@code SkullMeta#setPlayerProfile}) rather than reflecting
     * into com.mojang.authlib internals - this plugin already depends on paper-api and Paper-only events
     * elsewhere (see io.papermc.paper imports throughout the listener package), so there is no reason to
     * take on the extra fragility of an undocumented private field just to avoid a compile-time Paper
     * dependency that already exists. A failure here (malformed base64, future API change) is logged
     * instead of swallowed, so a broken icon in config.yml shows up in the console instead of just quietly
     * rendering as a plain head with no explanation.
     */
    public static ItemBuilder customHead(String base64) {
        ItemBuilder b = new ItemBuilder(Material.PLAYER_HEAD);
        if (base64 == null || base64.isBlank()) {
            return b;
        }
        try {
            if (b.meta instanceof SkullMeta skullMeta) {
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        org.bukkit.Bukkit.createProfile(UUID.randomUUID(), null);
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", base64.trim()));
                skullMeta.setPlayerProfile(profile);
            }
        } catch (Exception ex) {
            org.bukkit.Bukkit.getLogger().warning("[CustomTag] Could not apply custom head texture from config.yml "
                    + "(gui.icons.* base64 value) - falling back to a plain player head. Check the value isn't "
                    + "truncated or corrupted. Cause: " + ex.getMessage());
        }
        return b;
    }

    /** Returns a custom-head builder if base64 is set in config, otherwise a plain builder using the fallback material. */
    public static ItemBuilder icon(String base64, Material fallback) {
        if (base64 != null && !base64.isBlank()) {
            return customHead(base64);
        }
        return new ItemBuilder(fallback);
    }

    public ItemBuilder name(String legacyOrMiniMessage) {
        meta.displayName(ColorUtil.parse(legacyOrMiniMessage).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        List<net.kyori.adventure.text.Component> comps = new ArrayList<>();
        for (String l : lines) {
            comps.add(ColorUtil.parse(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(comps);
        return this;
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, amount));
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (glow) {
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }

    /** Tags this item with an identifying string key so click listeners can recognise it. */
    public ItemBuilder tag(NamespacedKey key, String value) {
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        return this;
    }

    public ItemStack build() {
        stack.setItemMeta(meta);
        return stack;
    }
}
