package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.data.PlayerData;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A pluggable place to persist {@link PlayerData}. The plugin ships three implementations
 * (see {@code storage.type} in config.yml):
 *
 *   yaml    -> {@link YamlStorageBackend}    - one file per player, zero setup, always works.
 *   mysql   -> {@link MySQLStorageBackend}   - single shared table, good for large/multi-server setups.
 *   mongodb -> {@link MongoStorageBackend}   - one document per player, good for large/multi-server setups.
 *
 * IMPORTANT SAFETY CONTRACT: {@link #init()} must throw on any connection failure so the caller
 * ({@link com.mellishy.customtag.data.DataManager}) can catch it and fall back to
 * {@link YamlStorageBackend} automatically. A misconfigured or unreachable database must NEVER
 * crash the plugin or block startup - see DataManager#init for the fallback logic.
 */
public interface StorageBackend {

    /** Opens the connection / pool and prepares schema (tables, indexes, ...). Must throw on failure. */
    void init() throws Exception;

    /** Loads every known player's data into memory. Called once at startup. */
    Map<UUID, PlayerData> loadAll() throws Exception;

    /**
     * Loads exactly one player's data, if any exists yet. Used by
     * {@link com.mellishy.customtag.listener.PlayerPreLoginListener} to lazily repopulate the cache
     * right before a returning player joins, on servers large enough that the cache no longer keeps
     * every player who has EVER connected in memory for the whole session (see
     * {@link com.mellishy.customtag.data.DataManager} eviction). Safe to call from a background
     * thread - callers never invoke this on the main thread.
     */
    Optional<PlayerData> load(UUID uuid) throws Exception;

    /** Persists a single player's data. Must be safe to call very frequently (every mutation). */
    void save(PlayerData data) throws Exception;

    /** Persists every given player's data, e.g. on plugin disable. */
    void saveAll(Collection<PlayerData> data) throws Exception;

    /** Releases the connection / pool. Called on plugin disable. */
    void close();

    /** Short machine name used in log messages, e.g. "YAML", "MySQL", "MongoDB". */
    String name();
}