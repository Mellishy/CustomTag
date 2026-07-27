package com.mellishy.customtag.module;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Foundation of the modular configuration architecture: every subsystem
 * (blacklist, queue, ai, webhooks, tokens, security, permissions, logs, network, ...) keeps its
 * settings in its OWN folder under the plugin data directory instead of piling hundreds of keys
 * into one monolithic config.yml. Each file is:
 *
 * <ul>
 *   <li><b>Auto-generated</b> - a missing module folder/file is recreated from the bundled default
 *       shipped inside the jar, so admins can safely delete a broken file to reset it.</li>
 *   <li><b>Versioned + migrated</b> - every bundled default carries a {@code version} key. When the
 *       jar ships a newer version than the file on disk, keys the admin never touched are merged in
 *       (existing values are NEVER overwritten), so plugin updates cannot destroy user settings.</li>
 *   <li><b>Independently reloadable</b> - {@code /customtag reload <module>} drops only that
 *       module's cached configs; nothing else is disturbed (hot reload, no restart needed).</li>
 * </ul>
 *
 * THREAD SAFETY: the cache is a {@link ConcurrentHashMap} and {@link YamlConfiguration} reads are
 * safe once loaded; loading/migration itself only ever happens on the calling thread (main thread
 * at startup or during a reload command). Services should read their config once per reload and
 * keep parsed, immutable state - not query this on every hot-path call.
 */
public class ModuleConfigService {

    private final JavaPlugin plugin;
    /** Keyed "module/file.yml" -> parsed configuration. */
    private final Map<String, YamlConfiguration> cache = new ConcurrentHashMap<>();

    /** Every module folder this service has been asked for - drives tab completion of /ct reload. */
    private final Set<String> knownModules = ConcurrentHashMap.newKeySet();

    public ModuleConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the configuration for {@code <dataFolder>/<module>/<fileName>} (fileName should
     * include the .yml extension), generating it from the bundled default and migrating missing
     * keys first when needed. Never returns null - a completely missing/broken file yields an
     * empty configuration so callers can rely on their own defaults.
     */
    public YamlConfiguration config(String module, String fileName) {
        String key = module + "/" + fileName;
        knownModules.add(module);
        return cache.computeIfAbsent(key, k -> loadOrCreate(module, fileName));
    }

    /** Drops the cached configs of one module so the next read reloads them from disk. */
    public void reloadModule(String module) {
        cache.keySet().removeIf(k -> k.startsWith(module + "/"));
    }

    /** Drops every cached module config (full reload). */
    public void reloadAll() {
        cache.clear();
    }

    /** Module folder names that have been requested this session - used for tab completion. */
    public Set<String> knownModules() {
        return Set.copyOf(knownModules);
    }

    private YamlConfiguration loadOrCreate(String module, String fileName) {
        String resourcePath = module + "/" + fileName;
        File file = new File(new File(plugin.getDataFolder(), module), fileName);

        if (!file.exists()) {
            saveBundledDefault(resourcePath, file);
        }

        YamlConfiguration cfg = new YamlConfiguration();
        try {
            if (file.exists()) {
                cfg.load(file);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "[CustomTag] Could not parse " + resourcePath
                    + " - using built-in defaults for this session. Fix or delete the file and reload.", ex);
            YamlConfiguration bundled = loadBundled(resourcePath);
            return bundled != null ? bundled : new YamlConfiguration();
        }

        migrateIfOutdated(resourcePath, file, cfg);
        return cfg;
    }

    /** Copies the default file shipped inside the jar to disk; creates parent folders as needed. */
    private void saveBundledDefault(String resourcePath, File target) {
        if (plugin.getResource(resourcePath) == null) {
            return; // no bundled default for this file - caller falls back to code defaults
        }
        try {
            // saveResource keeps the folder structure of the resource path, which is exactly
            // the modular layout we want (plugins/CustomTag/<module>/<file>.yml)
            plugin.saveResource(resourcePath, false);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[CustomTag] Failed to write default " + resourcePath, ex);
        }
        if (!target.exists()) {
            try {
                target.getParentFile().mkdirs();
                target.createNewFile();
            } catch (IOException ignored) {
                // an unwritable data folder is reported by saveResource above already
            }
        }
    }

    /**
     * Version-aware merge: when the bundled default declares a newer {@code version} than the file
     * on disk, every key that exists in the default but NOT in the user's file is copied over and
     * the version is bumped. User-set values are never overwritten - config migration must never
     * destroy an admin's settings.
     */
    private void migrateIfOutdated(String resourcePath, File file, YamlConfiguration current) {
        YamlConfiguration bundled = loadBundled(resourcePath);
        if (bundled == null) return;
        int bundledVersion = bundled.getInt("version", 1);
        int currentVersion = current.getInt("version", 1);
        if (bundledVersion <= currentVersion) return;

        boolean changed = false;
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (!current.isSet(path)) {
                current.set(path, bundled.get(path));
                changed = true;
            }
        }
        current.set("version", bundledVersion);
        if (changed || bundledVersion != currentVersion) {
            try {
                current.save(file);
                plugin.getLogger().info("[CustomTag] Migrated " + resourcePath + " from version "
                        + currentVersion + " to " + bundledVersion + " (existing values kept).");
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "[CustomTag] Could not save migrated " + resourcePath, ex);
            }
        }
    }

    private YamlConfiguration loadBundled(String resourcePath) {
        var stream = plugin.getResource(resourcePath);
        if (stream == null) return null;
        try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[CustomTag] Bundled default " + resourcePath + " is unreadable.", ex);
            return null;
        }
    }

    /**
     * Reads a plain-text bundled resource (e.g. an AI prompt file), writing the bundled default to
     * disk first if the file does not exist yet. Returns the ON-DISK content so admins can fully
     * customize it, falling back to the bundled copy and finally to {@code fallback}.
     */
    public String textFile(String module, String fileName, String fallback) {
        String resourcePath = module + "/" + fileName;
        File file = new File(new File(plugin.getDataFolder(), module), fileName);
        if (!file.exists()) {
            saveBundledDefault(resourcePath, file);
        }
        try {
            if (file.exists() && file.length() > 0) {
                return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "[CustomTag] Could not read " + resourcePath, ex);
        }
        try (var stream = plugin.getResource(resourcePath)) {
            if (stream != null) {
                return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    /** Convenience: string list with default. */
    public static List<String> list(YamlConfiguration cfg, String path) {
        return cfg.getStringList(path);
    }
}
