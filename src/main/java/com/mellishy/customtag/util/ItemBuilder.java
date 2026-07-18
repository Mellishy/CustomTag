package com.mellishy.customtag.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.NamespacedKey;
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
     * player head if the base64 is blank/invalid, or if this server's internals don't match
     * the expected shape, so a bad config value or an unusual server fork can never break a GUI.
     *
     * Implemented via reflection into com.mojang.authlib.GameProfile (the same technique used by
     * SkullCreator and most head-plugins) instead of org.bukkit.profile.PlayerProfile /
     * com.destroystokyo.paper.profile.PlayerProfile, because the exact API shape for those two
     * differs between Spigot/Paper versions and builds. Reflection sidesteps that entirely -
     * there is no compile-time dependency on either package here.
     */
    public static ItemBuilder customHead(String base64) {
        ItemBuilder b = new ItemBuilder(Material.PLAYER_HEAD);
        if (base64 == null || base64.isBlank()) {
            return b;
        }
        try {
            if (b.meta instanceof SkullMeta skullMeta) {
                Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
                Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");

                Object gameProfile = gameProfileClass.getConstructor(UUID.class, String.class)
                        .newInstance(UUID.randomUUID(), null);
                Object property = propertyClass.getConstructor(String.class, String.class)
                        .newInstance("textures", base64.trim());

                Object propertyMap = gameProfileClass.getMethod("getProperties").invoke(gameProfile);
                propertyMap.getClass().getMethod("put", Object.class, Object.class)
                        .invoke(propertyMap, "textures", property);

                java.lang.reflect.Field profileField = skullMeta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(skullMeta, gameProfile);
            }
        } catch (Exception ignored) {
            // malformed base64, or this server's internals don't match the expected shape -
            // just fall back to a plain head instead of breaking the GUI
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
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
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
