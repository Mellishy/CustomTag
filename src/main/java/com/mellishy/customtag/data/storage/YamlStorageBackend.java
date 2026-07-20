package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One YAML file per player under {@code <plugin-data-folder>/<storage.folder>/<uuid>.yml}.
 * This is the plugin's default backend - always available, no external service required - and is
 * also the automatic fallback used by {@link com.mellishy.customtag.data.DataManager} whenever a
 * configured MySQL/MongoDB backend fails to connect, so tag data is never lost or left unreadable.
 */
public class YamlStorageBackend implements StorageBackend {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private File folder;

    /**
     * BUGFIX: {@link #saveAll(java.util.Collection)} used to save every player sequentially
     * (a plain {@code forEach}), unlike {@link MySQLStorageBackend} and {@link MongoStorageBackend},
     * which both save their full batch in parallel. On a server with a large player base still on
     * the default YAML backend, that made {@code DataManager#shutdown()} - and therefore the whole
     * server's shutdown/reload - take needlessly long, since one slow disk write blocked every
     * other player's write behind it for no reason. Each save is an independent file, so there's no
     * correctness reason to serialize them. Deliberately its own small pool, not
     * {@code parallelStream()}'s shared common {@code ForkJoinPool} - same reasoning as the pools in
     * {@link MySQLStorageBackend}/{@link MongoStorageBackend}: that pool is shared with the rest of
     * the JVM (server internals, other plugins), and a large save batch during shutdown shouldn't
     * compete with unrelated parallel work happening at the same time.
     */
    private final ExecutorService saveAllExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "CustomTag-Yaml-SaveAll");
                t.setDaemon(true);
                return t;
            });

    public YamlStorageBackend(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void init() {
        folder = new File(plugin.getDataFolder(), configManager.storageFolder());
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create playerdata folder!");
        }
    }

    @Override
    public Map<UUID, PlayerData> loadAll() {
        Map<UUID, PlayerData> out = new ConcurrentHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            try {
                UUID uuid = UUID.fromString(f.getName().replace(".yml", ""));
                PlayerData data = loadInternal(uuid);
                if (data != null) out.put(uuid, data);
            } catch (IllegalArgumentException ignored) {
                // not a uuid-named file, skip
            }
        }
        return out;
    }

    @Override
    public Optional<PlayerData> load(UUID uuid) {
        return Optional.ofNullable(loadInternal(uuid));
    }

    private PlayerData loadInternal(UUID uuid) {
        File file = new File(folder, uuid + ".yml");
        if (!file.exists()) return null;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        PlayerData data = new PlayerData(uuid, yml.getString("name", uuid.toString()), yml.getInt("tokens", configManager.startingTokens()));
        data.setActiveTagId(yml.getString("active-tag", null));
        data.setCooldownUntil(yml.getLong("cooldown-until", 0L));
        data.setReservationActive(yml.getBoolean("reservation-active", false));
        data.setReservationId(yml.getString("reservation-id", null));
        data.setPendingNotice(yml.getString("pending-notice", null));
        data.setPendingNoticeResume(yml.getBoolean("pending-notice-resume", false));
        data.setRandomTagEnabled(yml.getBoolean("random-tag-enabled", false));
        data.getRandomTagPool().addAll(yml.getStringList("random-tag-pool"));

        List<Map<?, ?>> list = yml.getMapList("tags");
        for (Map<?, ?> m : list) {
            String id = String.valueOf(m.get("id"));
            String text = String.valueOf(m.get("text"));
            TagStatus status;
            try {
                status = TagStatus.valueOf(String.valueOf(m.get("status")));
            } catch (Exception ex) {
                status = TagStatus.REJECTED;
            }
            long created = m.get("created") != null ? Long.parseLong(String.valueOf(m.get("created"))) : System.currentTimeMillis();
            // fall back to `created` only for rows saved before this field existed - not a normal case going forward
            long updated = m.get("updated") != null ? Long.parseLong(String.valueOf(m.get("updated"))) : created;
            TagEntry entry = new TagEntry(id, uuid, text, status, created, updated);
            Object reason = m.get("reason");
            if (reason != null) entry.setRejectReason(String.valueOf(reason));
            data.getTags().add(entry);
        }
        return data;
    }

    @Override
    public void save(PlayerData data) {
        if (data == null) return;
        File file = new File(folder, data.getUuid() + ".yml");
        // Write to a temp file first, then atomically move it into place - see saveToFile() below for
        // why: writing straight to `file` risked leaving a half-written, corrupt YAML file behind if
        // the server crashed or lost power mid-write (e.g. right between save() calls during a busy
        // admin session), which would silently wipe that one player's tags/tokens on next load.
        saveToFile(data, file);
    }

    private void saveToFile(PlayerData data, File file) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("name", data.getLastKnownName());
        yml.set("tokens", data.getTokens());
        yml.set("active-tag", data.getActiveTagId());
        yml.set("cooldown-until", data.getCooldownUntil());
        yml.set("reservation-active", data.isReservationActive());
        yml.set("reservation-id", data.getReservationId());
        yml.set("pending-notice", data.getPendingNotice());
        yml.set("pending-notice-resume", data.isPendingNoticeResume());
        yml.set("random-tag-enabled", data.isRandomTagEnabled());
        yml.set("random-tag-pool", data.getRandomTagPool());

        List<Map<String, Object>> tagList = data.getTags().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("text", t.getRawText());
            m.put("status", t.getStatus().name());
            m.put("created", t.getCreatedAt());
            m.put("updated", t.getUpdatedAt());
            if (t.getRejectReason() != null) m.put("reason", t.getRejectReason());
            return m;
        }).toList();
        yml.set("tags", tagList);

        // Atomic write: save to a sibling ".tmp" file first, then move it over the real file in one
        // filesystem operation. ATOMIC_MOVE means there is never a window where `file` exists but is
        // only partially written - a crash before the move leaves the old file untouched, a crash
        // after the move leaves the new one fully intact. Either way the player's data is never
        // observed half-written on the next load.
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            yml.save(tempFile);
            java.nio.file.Files.move(tempFile.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            // some filesystems (notably certain network mounts) don't support atomic moves across
            // the same call - fall back to a plain (non-atomic, but still far better than writing the
            // real file directly) move rather than failing the save outright.
            try {
                java.nio.file.Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception fallback) {
                plugin.getLogger().warning("Failed to save player data for " + data.getUuid() + ": " + fallback.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save player data for " + data.getUuid() + ": " + e.getMessage());
        } finally {
            // if the move already succeeded this is a no-op (nothing left at tempFile); if saving or
            // moving failed partway through, this makes sure a stray .tmp file is never left behind
            if (tempFile.exists() && !tempFile.delete()) {
                plugin.getLogger().fine("Could not clean up leftover temp file: " + tempFile.getName());
            }
        }
    }

    /**
     * Runs in parallel on {@link #saveAllExecutor} - see that field's javadoc for why this changed
     * from a sequential {@code forEach}. Blocks (with a 30-second safety timeout, matching
     * {@link MySQLStorageBackend#saveAll}/{@link MongoStorageBackend#saveAll}) until every save in
     * the batch has actually finished, so callers - namely {@code DataManager#shutdown()} - can rely
     * on saveAll() having genuinely completed, not just having been "queued".
     */
    @Override
    public void saveAll(java.util.Collection<PlayerData> data) {
        List<CompletableFuture<Void>> futures = data.stream()
                .map(d -> CompletableFuture.runAsync(() -> save(d), saveAllExecutor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().warning("Timed out or failed while waiting for the YAML saveAll batch to finish: " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        saveAllExecutor.shutdown();
        try {
            if (!saveAllExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveAllExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveAllExecutor.shutdownNow();
        }
    }

    @Override
    public String name() {
        return "YAML";
    }
}