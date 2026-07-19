package com.mellishy.customtag.data;

import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.storage.MongoStorageBackend;
import com.mellishy.customtag.data.storage.MySQLStorageBackend;
import com.mellishy.customtag.data.storage.StorageBackend;
import com.mellishy.customtag.data.storage.YamlStorageBackend;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the in-memory player-data cache and delegates persistence to a {@link StorageBackend}
 * (YAML, MySQL or MongoDB - see storage.type in config.yml). Every player (online or not) stays
 * cached in memory for the whole session regardless of which backend is active, so the admin GUI,
 * random-tag rotation, etc. always see instant, consistent data - tags never "jump" or lose their
 * selected/pending state because of network latency to an external database.
 *
 * SAFETY: if the configured backend is MySQL or MongoDB and it fails to connect for ANY reason
 * (unreachable host, bad credentials, driver/service down, ...), {@link #init()} catches that
 * failure, logs a clear warning, and transparently falls back to the YAML backend instead of
 * crashing the plugin or leaving it without persistence.
 *
 * ---- Why saves are asynchronous ----
 * Every mutation in this plugin (approve, reject, select, delete, edit, random-toggle, ...) ends
 * with a call to {@link #save(PlayerData)}. On a small server that's rare enough to not matter even
 * if it blocks; on a large one, dozens of players clicking GUI buttons at once used to mean dozens
 * of blocking disk writes (YAML) or blocking network round-trips (MySQL/MongoDB) happening directly
 * on the main thread - a guaranteed source of TPS drops and, if the database ever hiccups, a frozen
 * server. {@link #save(PlayerData)} now updates the in-memory cache instantly (so nothing in this
 * plugin ever reads stale data) and hands the actual persistence off to {@link #ioExecutor}.
 *
 * Writes for the SAME player are still applied strictly in order (chained per-UUID via
 * {@link #writeChains}), so a fast double-click can never let an older save overwrite a newer one -
 * writes for DIFFERENT players run fully in parallel across the pool.
 */
public class DataManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private StorageBackend backend;

    /** Small fixed pool - persistence work is I/O-bound (disk/network wait), not CPU-bound, so a
     *  handful of threads is enough to keep many concurrent players' writes from queuing behind
     *  each other while still never touching the main thread. */
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "CustomTag-IO");
                t.setDaemon(true);
                return t;
            });
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    /**
     * Immutable, fully-copied view of exactly the fields chat rendering / PlaceholderAPI need.
     * Safe to read from ANY thread - unlike {@link PlayerData}, which (as documented on
     * {@link PlayerData#snapshot()}) is a plain, unsynchronized mutable object that is only safe
     * to touch from the main thread.
     */
    public record RenderSnapshot(String activeTagRaw, boolean randomEnabled, List<String> randomPoolRaw,
                                  int tokens, int tagCount) {
        private static final RenderSnapshot EMPTY = new RenderSnapshot(null, false, List.of(), 0, 0);
    }

    /**
     * THREAD SAFETY: both {@link com.mellishy.customtag.listener.ChatTagListener} (chat is rendered
     * off the main thread via Paper's {@code AsyncChatEvent}) and {@link com.mellishy.customtag.placeholder.MellishyPlaceholder}
     * (PlaceholderAPI is free to call {@code onRequest} from whatever thread the requesting
     * plugin/GUI/scoreboard uses, including async ones) are the only two places in this plugin that
     * need a player's tag data from a thread other than the main one. Every other read/write in this
     * plugin goes through the live {@link PlayerData} object on the main thread, which is fine
     * because that object has no internal synchronization by design.
     *
     * Handing either of those two callers the live object would be a real, hard-to-reproduce bug:
     * the main thread can be mutating {@code data.getTags()} (an unsynchronized {@code ArrayList})
     * at the exact moment an async chat/placeholder thread iterates it, which can throw a
     * {@code ConcurrentModificationException} or observe a half-updated player mid-admin-action.
     * This small cache is the fix - a fully immutable {@link RenderSnapshot} rebuilt on the main
     * thread every time {@link #save} runs, so off-thread readers only ever see a frozen, safe copy.
     */
    private final Map<UUID, RenderSnapshot> renderCache = new ConcurrentHashMap<>();

    public DataManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    private void refreshRenderCache(PlayerData data) {
        String active = data.getActiveTag().map(TagEntry::getRawText).orElse(null);
        boolean randomOn = data.isRandomTagEnabled() && data.approvedTagCount() >= configManager.randomMinTags();
        List<String> pool = randomOn
                ? data.resolveRandomPool().stream().map(TagEntry::getRawText).toList()
                : List.of();
        renderCache.put(data.getUuid(), new RenderSnapshot(active, randomOn, pool, data.getTokens(), data.getTags().size()));
    }

    /**
     * Thread-safe read used by {@link com.mellishy.customtag.listener.ChatTagListener} and
     * {@link com.mellishy.customtag.placeholder.MellishyPlaceholder}. Never returns null - a player
     * with no snapshot yet (e.g. queried a split second before their join-triggered save completes)
     * simply gets the safe "no tag yet" default, exactly matching a real freshly-created PlayerData.
     */
    public RenderSnapshot renderSnapshot(UUID uuid) {
        return renderCache.getOrDefault(uuid, RenderSnapshot.EMPTY);
    }

    public void init() {
        String type = configManager.storageType();
        backend = createBackend(type);

        try {
            backend.init();
            plugin.getLogger().info("CustomTag storage backend: " + backend.name());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not connect to the configured '" + type
                    + "' storage backend (" + ex.getMessage() + "). Falling back to safe YAML storage - "
                    + "no data was lost, but check your storage.* settings in config.yml.", ex);
            backend = new YamlStorageBackend(plugin, configManager);
            try {
                backend.init();
            } catch (Exception fatal) {
                // the YAML fallback itself should never realistically fail, but never let a storage
                // problem take the whole plugin down - log it and keep running on an in-memory-only cache
                plugin.getLogger().log(Level.SEVERE, "YAML fallback storage also failed to initialize! Data will not persist this session.", fatal);
                backend = new NoopBackend();
            }
        }

        try {
            Map<UUID, PlayerData> loaded = backend.loadAll();
            cache.putAll(loaded);
            loaded.values().forEach(this::refreshRenderCache);
            warnIfLargePlayerbaseWithoutEviction(loaded.size());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player data from " + backend.name() + " storage.", ex);
        }
    }

    /** Large enough that "every player who ever joined stays cached forever" (the default, see class
     *  javadoc) starts being worth a heads-up rather than staying a silent, undocumented cost. */
    private static final int LARGE_PLAYERBASE_THRESHOLD = 5000;

    private void warnIfLargePlayerbaseWithoutEviction(int loadedCount) {
        if (configManager.cacheEvictionEnabled() || loadedCount < LARGE_PLAYERBASE_THRESHOLD) return;
        plugin.getLogger().warning("[CustomTag] Loaded " + loadedCount + " players' data at startup and "
                + "storage.cache-eviction is disabled - every one of them will stay cached in memory for "
                + "the rest of this session, and this same startup read happens again in full on every "
                + "restart. Consider enabling storage.cache-eviction in config.yml if memory or startup "
                + "time becomes a concern.");
    }

    private StorageBackend createBackend(String type) {
        return switch (type == null ? "yaml" : type.toLowerCase().trim()) {
            case "mysql" -> new MySQLStorageBackend(plugin, configManager);
            case "mongodb", "mongo" -> new MongoStorageBackend(plugin, configManager);
            default -> new YamlStorageBackend(plugin, configManager);
        };
    }

    public PlayerData get(UUID uuid, String nameIfNew) {
        boolean[] created = {false};
        PlayerData data = cache.computeIfAbsent(uuid, id -> {
            created[0] = true;
            return new PlayerData(id, nameIfNew, configManager.startingTokens());
        });
        // a brand-new player has no tag/tokens history yet, but still deserves a valid (empty)
        // RenderSnapshot immediately - otherwise an async chat message sent before their join-time
        // save() completes would fall back to the "player not cached at all" default, which happens
        // to look the same here but shouldn't be left to chance.
        if (created[0]) refreshRenderCache(data);
        return data;
    }

    public Map<UUID, PlayerData> all() {
        return cache;
    }

    /**
     * Ensures this player's data is in the cache, loading it from the backend first if it isn't -
     * used by {@link com.mellishy.customtag.listener.PlayerPreLoginListener} to repopulate a player
     * who was previously evicted (see {@link #scheduleEviction}) before they actually join. Safe to
     * call from any thread; only touches the backend when the cache doesn't already have the player.
     */
    public void ensureLoaded(UUID uuid, String nameIfNew) {
        if (cache.containsKey(uuid)) return;
        try {
            Optional<PlayerData> loaded = backend.load(uuid);
            PlayerData data = loaded.orElseGet(() -> new PlayerData(uuid, nameIfNew, configManager.startingTokens()));
            cache.put(uuid, data);
            refreshRenderCache(data);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to lazily load player data for " + uuid + " via " + backend.name() + " storage.", ex);
            PlayerData fallback = new PlayerData(uuid, nameIfNew, configManager.startingTokens());
            if (cache.putIfAbsent(uuid, fallback) == null) refreshRenderCache(fallback);
        }
    }

    /**
     * Opt-in memory optimization for very large player bases (see storage.cache-eviction in
     * config.yml, OFF by default). Every player who has ever connected stays cached for the whole
     * session by default - fine up to tens of thousands of unique accounts, but on a server with a
     * much larger cumulative playerbase this lets memory grow without bound over a long uptime.
     * When enabled, an offline player's data is dropped from the cache a configurable delay after
     * they disconnect (never immediately - a quick reconnect just keeps them cached) and
     * transparently reloaded by {@link com.mellishy.customtag.listener.PlayerPreLoginListener}
     * next time they connect, so nothing is ever lost - only held in memory for as long as needed.
     */
    public void scheduleEviction(UUID uuid) {
        if (!configManager.cacheEvictionEnabled()) return;
        long delayTicks = configManager.cacheEvictionDelaySeconds() * 20L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getServer().getPlayer(uuid) != null) return; // reconnected - keep them cached
            PlayerData data = cache.get(uuid);
            // a pending request must stay visible in the admin queue (AdminGUI reads straight from
            // this cache) regardless of whether the requester is online - never evict those
            if (data != null && data.getPendingTag().isPresent()) return;
            cache.remove(uuid);
            renderCache.remove(uuid);
        }, delayTicks);
    }

    /**
     * Queues this player's data to be persisted. Returns immediately - the in-memory cache is
     * already updated by the time this method returns, so every other part of the plugin sees the
     * new state instantly even though the actual write happens shortly after, off the main thread.
     *
     * THREAD SAFETY: {@link PlayerData} (and the {@link com.mellishy.customtag.data.TagEntry}
     * objects it holds) are plain mutable fields with no synchronization - deliberately, since every
     * other access to them happens on the main thread. That means the live {@code data} instance
     * must never be read directly by the I/O executor: the main thread is free to keep calling
     * setters (or add/remove entries from {@code data.getTags()}) at any moment, including while a
     * previously-queued save for the SAME player is still serializing that object on another
     * thread - a classic recipe for a torn read or a {@code ConcurrentModificationException} while
     * a storage backend iterates the tags list. We take an immutable {@link PlayerData#snapshot()}
     * synchronously, right here on the calling (main) thread, before anything is handed off - the
     * async task below only ever touches that private copy, never the live object.
     */
    public void save(PlayerData data) {
        if (data == null) return;
        cache.put(data.getUuid(), data);
        // must run here, synchronously, on whatever thread called save() (always the main thread in
        // practice - see the RenderSnapshot javadoc above) so ChatTagListener/MellishyPlaceholder
        // never see stale tag/token data after a save.
        refreshRenderCache(data);

        UUID uuid = data.getUuid();
        PlayerData snapshot = data.snapshot();
        writeChains.compute(uuid, (id, previous) -> {
            CompletableFuture<Void> prior = previous != null ? previous : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> next = prior.thenRunAsync(() -> {
                try {
                    backend.save(snapshot);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save player data for " + uuid + " via " + backend.name() + " storage.", ex);
                }
            }, ioExecutor);
            // once this link finishes, drop it from the map (unless a newer save already replaced
            // it) so writeChains never grows without bound across a long-running session
            next.whenComplete((r, ex) -> writeChains.remove(uuid, next));
            return next;
        });
    }

    /** Blocks until every currently-queued async save has finished. Only called on plugin disable. */
    public void awaitPendingSaves(long timeoutSeconds) {
        CompletableFuture<?>[] pending = writeChains.values().toArray(new CompletableFuture[0]);
        if (pending.length == 0) return;
        try {
            CompletableFuture.allOf(pending).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for in-flight saves during shutdown - "
                    + "the immediately-following synchronous saveAll() below covers the final state regardless.", ex);
        }
    }

    public void saveAll() {
        try {
            backend.saveAll(cache.values());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save all player data via " + backend.name() + " storage.", ex);
        }
    }

    /**
     * Full, ordered shutdown sequence - call this alone from {@code onDisable}, don't call
     * {@link #saveAll()} separately beforehand. Order matters: we first drain every already-queued
     * async save (each holds its own frozen snapshot from whenever it was queued), THEN do one final
     * synchronous {@link #saveAll()} of the current cache. Doing it in the other order would let an
     * older, in-flight async write - queued before disable but not yet executed - run AFTER the
     * "final" save and silently overwrite it with stale data.
     */
    public void shutdown() {
        awaitPendingSaves(10);
        saveAll();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
        if (backend != null) backend.close();
    }

    /** Last-resort backend used only if even the YAML fallback fails to initialize - keeps the plugin alive. */
    private static class NoopBackend implements StorageBackend {
        @Override public void init() {}
        @Override public Map<UUID, PlayerData> loadAll() { return new ConcurrentHashMap<>(); }
        @Override public java.util.Optional<PlayerData> load(UUID uuid) { return java.util.Optional.empty(); }
        @Override public void save(PlayerData data) {}
        @Override public void saveAll(java.util.Collection<PlayerData> data) {}
        @Override public void close() {}
        @Override public String name() { return "None (in-memory only)"; }
    }
}
