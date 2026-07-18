package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
/**
 * Stores every player as one BSON document in a MongoDB collection. Recommended alongside MySQL for
 * large / multi-server networks. Connection settings live under storage.mongodb in config.yml.
 *
 * Same safety contract as {@link MySQLStorageBackend}: any failure in {@link #init()} (bad connection
 * string, unreachable cluster, auth failure, ...) must propagate as an exception so
 * {@link com.mellishy.customtag.data.DataManager} can fall back to {@link YamlStorageBackend} instead
 * of crashing the plugin.
 */
public class MongoStorageBackend implements StorageBackend {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private MongoClient client;
    private MongoCollection<Document> collection;

    /**
     * Dedicated pool used only by {@link #saveAll(Collection)} - same reasoning as
     * {@link MySQLStorageBackend#saveAllExecutor}: {@code parallelStream()}'s default common
     * {@code ForkJoinPool} is shared with the rest of the JVM (server internals, other plugins),
     * so a large save batch during shutdown shouldn't be competing with unrelated parallel work on
     * it. {@link MongoClient} manages its own connection pool internally and is documented as
     * thread-safe, so handing it work from several threads here is still exactly as safe/parallel
     * as before - only the thread pool itself changed.
     */
    private final ExecutorService saveAllExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "CustomTag-Mongo-SaveAll");
                t.setDaemon(true);
                return t;
            });

    public MongoStorageBackend(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void init() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(configManager.mongoConnectionString()))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build();
        client = MongoClients.create(settings);
        MongoDatabase database = client.getDatabase(configManager.mongoDatabase());
        collection = database.getCollection(configManager.mongoCollection());

        // fail fast here (instead of on the first real query) so a bad connection string / unreachable
        // cluster is caught immediately by DataManager's try/catch and falls back to YAML right away
        database.runCommand(new Document("ping", 1));

        // Every per-player load()/save() filters by `uuid` (see below) - without an index on it,
        // MongoDB has no choice but to scan the ENTIRE collection on every single one of those calls
        // (every GUI click, chat message triggering a save, admin action, ...), which is fine on a
        // small server but degrades badly as the player base grows into the thousands. `unique(true)`
        // also gives the database itself a hard guarantee against ever storing two documents for the
        // same player - createIndex is a no-op if the index already exists, so this is always safe to
        // run on every startup.
        collection.createIndex(Indexes.ascending("uuid"), new IndexOptions().unique(true));
    }

    @Override
    public Map<UUID, PlayerData> loadAll() {
        Map<UUID, PlayerData> out = new HashMap<>();
        for (Document doc : collection.find()) {
            PlayerData data = fromDocument(doc);
            out.put(data.getUuid(), data);
        }
        return out;
    }

    private PlayerData fromDocument(Document doc) {
        UUID uuid = UUID.fromString(doc.getString("uuid"));
        PlayerData data = new PlayerData(uuid, doc.getString("name"), doc.getInteger("tokens", configManager.startingTokens()));
        data.setActiveTagId(doc.getString("activeTag"));
        data.setCooldownUntil(doc.get("cooldownUntil") != null ? doc.getLong("cooldownUntil") : 0L);
        data.setReservationActive(Boolean.TRUE.equals(doc.getBoolean("reservationActive")));
        data.setReservationId(doc.getString("reservationId"));
        data.setPendingNotice(doc.getString("pendingNotice"));
        data.setPendingNoticeResume(Boolean.TRUE.equals(doc.getBoolean("pendingNoticeResume")));
        data.setRandomTagEnabled(Boolean.TRUE.equals(doc.getBoolean("randomEnabled")));
        data.getRandomTagPool().addAll(TagJson.deserializePool(doc.getString("randomPool")));
        data.getTags().addAll(TagJson.deserializeTags(doc.getString("tagsJson"), uuid));
        return data;
    }

    @Override
    public void save(PlayerData data) {
        if (data == null || collection == null) return;
        Document doc = new Document("uuid", data.getUuid().toString())
                .append("name", data.getLastKnownName())
                .append("tokens", data.getTokens())
                .append("activeTag", data.getActiveTagId())
                .append("cooldownUntil", data.getCooldownUntil())
                .append("reservationActive", data.isReservationActive())
                .append("reservationId", data.getReservationId())
                .append("pendingNotice", data.getPendingNotice())
                .append("pendingNoticeResume", data.isPendingNoticeResume())
                .append("randomEnabled", data.isRandomTagEnabled())
                .append("randomPool", TagJson.serializePool(data.getRandomTagPool()))
                .append("tagsJson", TagJson.serializeTags(data.getTags()));

        collection.replaceOne(new Document("uuid", data.getUuid().toString()), doc, new ReplaceOptions().upsert(true));
    }

    /**
     * Runs in parallel, same reasoning as {@link MySQLStorageBackend#saveAll}: {@link MongoClient} is
     * documented as thread-safe and manages its own internal connection pool, so saving the full
     * player cache sequentially here only added unnecessary wall-clock time to plugin/server shutdown
     * on a large player base for no real safety benefit.
     */
    @Override
    public void saveAll(Collection<PlayerData> data) {
        List<CompletableFuture<Void>> futures = data.stream()
                .map(d -> CompletableFuture.runAsync(() -> {
                    try {
                        save(d);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to save Mongo document for " + d.getUuid() + ": " + e.getMessage());
                    }
                }, saveAllExecutor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().warning("Timed out or failed while waiting for the Mongo saveAll batch to finish: " + ex.getMessage());
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
        if (client != null) client.close();
    }
    
    @Override
    public Optional<PlayerData> load(UUID uuid) throws Exception {
        if (collection == null) return Optional.empty();
        
        Document doc = collection.find(new Document("uuid", uuid.toString())).first();
        if (doc != null) {
            return Optional.of(fromDocument(doc));
        }
        
        return Optional.empty();
    }

    @Override
    public String name() {
        return "MongoDB";
    }
}