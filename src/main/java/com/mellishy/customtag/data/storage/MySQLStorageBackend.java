package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Stores every player's data in ONE shared MySQL table (default name {@code ct_players}, configurable
 * via storage.mysql.table-prefix). Recommended for large / multi-server networks where flat YAML files
 * don't scale or can't be shared safely between servers.
 *
 * Backed by a HikariCP connection pool instead of one hand-rolled JDBC connection. A single shared
 * connection forced every single save/load in the whole plugin to queue behind each other (via a
 * {@code synchronized} lock) - fine at small scale, but a genuine bottleneck once dozens of players
 * are triggering saves at once (every GUI click, tag select, admin action, ...). Each call below now
 * borrows a connection from the pool for just the duration of that one query and returns it
 * immediately after, so saves for different players can run truly in parallel. Pool size is
 * deliberately modest (see {@link #buildDataSource()}) - this plugin's per-write payload is small, so
 * a large pool would just hold idle connections open on the database server for no benefit.
 *
 * If {@link #init()} throws for ANY reason (bad credentials, unreachable host, driver missing, ...) the
 * caller ({@link com.mellishy.customtag.data.DataManager}) catches it and transparently falls back to
 * {@link YamlStorageBackend} - this backend must never be the reason the plugin fails to start.
 */
public class MySQLStorageBackend implements StorageBackend {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final String table;
    private HikariDataSource dataSource;

    /**
     * Dedicated pool used only by {@link #saveAll(Collection)} - deliberately NOT
     * {@code parallelStream()}'s default, Java's shared common {@code ForkJoinPool}. That pool is
     * also used internally by the server itself and by any other plugin/library on the same JVM
     * that reaches for a parallel stream; borrowing it here for what can be a large batch of DB
     * writes during plugin shutdown risked starving unrelated parallel work happening elsewhere at
     * the exact same moment (server shutdown is when the most other plugins are ALSO doing cleanup
     * work). This pool is scoped to this backend alone and shut down alongside it in {@link #close()}.
     */
    private final ExecutorService saveAllExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "CustomTag-MySQL-SaveAll");
                t.setDaemon(true);
                return t;
            });

    /** Only characters that are always safe inside a backtick-quoted MySQL identifier, with no escaping needed. */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER_CHARS = java.util.regex.Pattern.compile("[^A-Za-z0-9_]");

    public MySQLStorageBackend(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.table = sanitizeIdentifier(configManager.mysqlTablePrefix()) + "players";
    }

    /**
     * {@code storage.mysql.table-prefix} is concatenated directly into the SQL statements below
     * (identifiers like table names can't be bound as JDBC {@code ?} parameters the way values
     * can) - so unlike every actual column VALUE in this class, which always goes through a
     * {@link PreparedStatement} placeholder, the prefix itself was previously trusted as-is. A
     * stray backtick, space, or SQL-significant character in that one config value could break
     * every query this backend runs, or in the worst case let a corrupted/tampered-with config
     * file influence the executed SQL. This strips anything outside {@code [A-Za-z0-9_]} so a bad
     * config value can only ever produce an unexpected (but always syntactically safe) table name,
     * never break out of the identifier position.
     */
    static String sanitizeIdentifier(String raw) {
        if (raw == null) return "";
        return SAFE_IDENTIFIER_CHARS.matcher(raw.trim()).replaceAll("");
    }

    @Override
    public void init() throws Exception {
        dataSource = buildDataSource();

        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` (" +
                    "`uuid` VARCHAR(36) NOT NULL PRIMARY KEY," +
                    "`name` VARCHAR(64) NOT NULL," +
                    "`tokens` INT NOT NULL DEFAULT 0," +
                    "`active_tag` VARCHAR(64)," +
                    "`cooldown_until` BIGINT NOT NULL DEFAULT 0," +
                    "`reservation_active` TINYINT(1) NOT NULL DEFAULT 0," +
                    "`reservation_id` VARCHAR(64)," +
                    "`pending_notice` TEXT," +
                    "`pending_notice_resume` TINYINT(1) NOT NULL DEFAULT 0," +
                    "`random_enabled` TINYINT(1) NOT NULL DEFAULT 0," +
                    "`random_pool` TEXT," +
                    "`tags_json` LONGTEXT" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private HikariDataSource buildDataSource() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://" + configManager.mysqlHost() + ":" + configManager.mysqlPort() + "/" + configManager.mysqlDatabase()
                + "?useSSL=" + configManager.mysqlUseSsl() + "&autoReconnect=true&characterEncoding=utf8&useUnicode=true");
        hikari.setUsername(configManager.mysqlUsername());
        hikari.setPassword(configManager.mysqlPassword());
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setPoolName("CustomTag-Hikari");

        // Small, bounded pool: this plugin does short single-row writes, not heavy analytical
        // queries, so a handful of connections comfortably serves even a very large player base -
        // what matters is that saves no longer share ONE connection, not that there are hundreds.
        hikari.setMaximumPoolSize(configManager.mysqlPoolSize());
        hikari.setMinimumIdle(Math.min(2, configManager.mysqlPoolSize()));
        hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        hikari.setIdleTimeout(TimeUnit.MINUTES.toMillis(5));
        hikari.setMaxLifetime(TimeUnit.MINUTES.toMillis(25));
        hikari.setKeepaliveTime(TimeUnit.MINUTES.toMillis(2));
        hikari.setInitializationFailTimeout(TimeUnit.SECONDS.toMillis(10));

        // sane MySQL performance defaults recommended by HikariCP's own docs
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");

        return new HikariDataSource(hikari);
    }

    @Override
    public Map<UUID, PlayerData> loadAll() throws SQLException {
        Map<UUID, PlayerData> out = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `" + table + "`")) {
            while (rs.next()) {
                PlayerData data = fromRow(rs);
                out.put(data.getUuid(), data);
            }
        }
        return out;
    }

    private PlayerData fromRow(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        PlayerData data = new PlayerData(uuid, rs.getString("name"), rs.getInt("tokens"));
        data.setActiveTagId(rs.getString("active_tag"));
        data.setCooldownUntil(rs.getLong("cooldown_until"));
        data.setReservationActive(rs.getBoolean("reservation_active"));
        data.setReservationId(rs.getString("reservation_id"));
        data.setPendingNotice(rs.getString("pending_notice"));
        data.setPendingNoticeResume(rs.getBoolean("pending_notice_resume"));
        data.setRandomTagEnabled(rs.getBoolean("random_enabled"));
        data.getRandomTagPool().addAll(TagJson.deserializePool(rs.getString("random_pool")));
        data.getTags().addAll(TagJson.deserializeTags(rs.getString("tags_json"), uuid));
        return data;
    }

    @Override
    public Optional<PlayerData> load(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM `" + table + "` WHERE `uuid` = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(fromRow(rs));
            }
        }
    }

    @Override
    public void save(PlayerData data) throws SQLException {
        if (data == null || dataSource == null) return;
        String sql = "INSERT INTO `" + table + "` " +
                "(uuid,name,tokens,active_tag,cooldown_until,reservation_active,reservation_id,pending_notice,pending_notice_resume,random_enabled,random_pool,tags_json) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE name=VALUES(name),tokens=VALUES(tokens),active_tag=VALUES(active_tag)," +
                "cooldown_until=VALUES(cooldown_until),reservation_active=VALUES(reservation_active),reservation_id=VALUES(reservation_id)," +
                "pending_notice=VALUES(pending_notice),pending_notice_resume=VALUES(pending_notice_resume)," +
                "random_enabled=VALUES(random_enabled),random_pool=VALUES(random_pool),tags_json=VALUES(tags_json)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, data.getLastKnownName());
            ps.setInt(3, data.getTokens());
            ps.setString(4, data.getActiveTagId());
            ps.setLong(5, data.getCooldownUntil());
            ps.setBoolean(6, data.isReservationActive());
            ps.setString(7, data.getReservationId());
            ps.setString(8, data.getPendingNotice());
            ps.setBoolean(9, data.isPendingNoticeResume());
            ps.setBoolean(10, data.isRandomTagEnabled());
            ps.setString(11, TagJson.serializePool(data.getRandomTagPool()));
            ps.setString(12, TagJson.serializeTags(data.getTags()));
            ps.executeUpdate();
        }
    }

    /**
     * Runs in parallel on {@link #saveAllExecutor} (not a simple sequential for-loop, and
     * deliberately not {@code parallelStream()} either - see that field's javadoc for why) - this
     * is only ever called with the FULL player cache, most importantly during
     * {@link com.mellishy.customtag.data.DataManager#shutdown()}. Each {@link #save} call already
     * borrows its own short-lived connection from the Hikari pool (see the class javadoc), so
     * saving one-by-one only serialized every player's write behind each other for no reason - on
     * a server with a large player base that turned plugin shutdown (and therefore the whole
     * server's shutdown/reload) into a needlessly long wait. This method blocks (with a 30-second
     * safety timeout) until every save in the batch has actually finished, so callers - namely
     * {@code DataManager#shutdown()} - can rely on saveAll() having genuinely completed before
     * moving on, not just having been "queued".
     */
    @Override
    public void saveAll(Collection<PlayerData> data) {
        List<CompletableFuture<Void>> futures = data.stream()
                .map(d -> CompletableFuture.runAsync(() -> {
                    try {
                        save(d);
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to save MySQL row for " + d.getUuid() + ": " + e.getMessage());
                    }
                }, saveAllExecutor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().warning("Timed out or failed while waiting for the MySQL saveAll batch to finish: " + ex.getMessage());
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public String name() {
        return "MySQL (HikariCP)";
    }
}