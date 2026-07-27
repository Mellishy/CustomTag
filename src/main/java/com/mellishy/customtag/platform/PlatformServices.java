package com.mellishy.customtag.platform;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.ai.AIConfigLoader;
import com.mellishy.customtag.ai.AIModerationService;
import com.mellishy.customtag.api.CustomTagAPI;
import com.mellishy.customtag.api.event.TokenBalanceChangeEvent;
import com.mellishy.customtag.audit.AuditCategory;
import com.mellishy.customtag.audit.AuditLogService;
import com.mellishy.customtag.id.PlayerIdService;
import com.mellishy.customtag.module.ModuleConfigService;
import com.mellishy.customtag.perm.PermissionService;
import com.mellishy.customtag.perm.RoleDefinition;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.request.RequestManager;
import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.security.SecurityService;
import com.mellishy.customtag.sync.NoopSyncService;
import com.mellishy.customtag.sync.RedisSyncService;
import com.mellishy.customtag.sync.SyncEvent;
import com.mellishy.customtag.sync.SyncService;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransaction;
import com.mellishy.customtag.token.TokenTransactionType;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.PersistentCounters;
import com.mellishy.customtag.validation.ValidationService;
import com.mellishy.customtag.webhook.WebhookConfigLoader;
import com.mellishy.customtag.webhook.WebhookEventType;
import com.mellishy.customtag.webhook.WebhookService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Composition root of the whole enterprise layer added on top of the original plugin: builds,
 * wires, configures, schedules and shuts down every platform service (queue, tokens, validation,
 * security, audit, AI, webhooks, permissions, custom ids, sync). {@link MellishyCustomTag}
 * creates exactly one of these in onEnable; everything else reaches the services through
 * {@code plugin.platform()} (or externally through {@link CustomTagAPI}).
 *
 * All services stay Bukkit-free internally - this class is where Bukkit specifics (scheduler,
 * main-thread hand-off, data folder paths) are injected into them.
 */
public class PlatformServices {

    private final MellishyCustomTag plugin;
    private final ModuleConfigService configs;

    /**
     * One single-threaded executor serializes EVERY platform file write (counters, ids, queue
     * store, ledger, audit, flags). One thread is deliberate: writes stay ordered per file, and
     * human-scale request volume can never saturate it.
     */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CustomTag-Platform-IO");
        t.setDaemon(true);
        return t;
    });

    /**
     * What the services actually receive instead of {@link #ioExecutor} itself.
     *
     * Once {@link #shutdown()} has drained the executor, any further submission throws
     * RejectedExecutionException - and the services queue writes from paths that legitimately
     * still run during disable (a quit event refunding a reservation, a final audit line). Letting
     * that exception out would surface as "Error occurred while disabling CustomTag" and abort the
     * rest of the shutdown sequence.
     *
     * The rejected task is RUN INLINE rather than dropped. Dropping it was silent data loss on the
     * paths that matter most: the token ledger row for a refund issued during disable, the audit
     * entry for the last staff decision of the session. Those are append-only writes of a few
     * hundred bytes and we are already doing synchronous flushes at this point in shutdown(), so
     * running them on the caller's thread costs nothing anyone can perceive - and unlike a dropped
     * task, it actually reaches disk.
     */
    private final Executor guardedIo;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final PersistentCounters counters;
    private final PlayerIdService playerIds;
    private final RequestManager requests;
    private final ValidationService validation;
    private final TokenService tokens;
    private final SecurityService security;
    private final AuditLogService audit;
    private final AIModerationService ai;
    private final WebhookService webhooks;
    private final PermissionService permissions;

    /** Rebuilt (never null) when /customtag reload network switches the backend. */
    private volatile SyncService sync;

    /**
     * Main-thread hand-off for async callbacks (AI results, incoming sync events). Guarded so a
     * callback arriving during shutdown is dropped instead of throwing
     * IllegalPluginAccessException.
     */
    private final Executor mainThread;

    // ---- parsed module settings kept as cheap volatile state ----
    private volatile String serverName = "server";
    private volatile long expireAfterMillis;
    private volatile long expireCheckTicks = 20L * 60 * 30;
    private volatile boolean refundStaffReject = true;
    private volatile boolean refundAiReject = true;
    private volatile boolean refundQueueExpired = true;

    private int expiryTaskId = -1;
    private int retentionTaskId = -1;

    public PlatformServices(MellishyCustomTag plugin) {
        this.plugin = plugin;
        this.configs = new ModuleConfigService(plugin);

        Consumer<Exception> errors = ex ->
                plugin.getLogger().log(Level.WARNING, "[CustomTag] Platform I/O error", ex);
        Path dataDir = new File(plugin.getDataFolder(), "data").toPath();
        Path logsDir = new File(plugin.getDataFolder(), "logs").toPath();

        this.mainThread = command -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, command);
            }
        };

        this.guardedIo = task -> {
            try {
                ioExecutor.execute(task);
            } catch (RejectedExecutionException ex) {
                try {
                    task.run();
                } catch (RuntimeException inline) {
                    // never let a trailing write abort the disable sequence - see field javadoc
                    plugin.getLogger().log(Level.FINE, "Inline shutdown write failed", inline);
                }
            }
        };

        this.counters = new PersistentCounters(dataDir.resolve("counters.json"), guardedIo, errors);
        this.playerIds = new PlayerIdService(dataDir.resolve("player-ids.json"), counters, guardedIo, errors);
        this.requests = new RequestManager(dataDir.resolve("requests.json"), counters, guardedIo,
                errors, System::currentTimeMillis);
        this.tokens = new TokenService(logsDir.resolve("tokens"), dataDir.resolve("token-freezes.json"),
                counters, guardedIo, errors, System::currentTimeMillis);
        this.security = new SecurityService(dataDir.resolve("security-flags.json"), guardedIo,
                errors, System::currentTimeMillis);
        this.audit = new AuditLogService(logsDir.resolve("audit"), guardedIo, errors, System::currentTimeMillis);
        this.validation = new ValidationService(configs, plugin.getLogger());
        this.permissions = new PermissionService(configs, plugin.getLogger());
        this.ai = new AIModerationService(plugin.getLogger(), mainThread);
        this.webhooks = new WebhookService(plugin.getLogger(), http);

        applyNetworkSettings();
        this.sync = createSyncService();
        applyQueueSettings();
        applySecuritySettings();
        applyTokenSettings();
        applyLogSettings();
        AIConfigLoader.apply(ai, configs, http, plugin.getLogger());
        applyWebhookSettings();

        CustomTagAPI.register(new CustomTagAPI.Services(requests, tokens, playerIds, validation,
                ai, webhooks, audit, security, permissions));

        audit.log(AuditCategory.SYSTEM, "startup", "system", null, null, null,
                "platform enabled on server '" + serverName + "', sync backend: " + sync.name());
    }

    /** Schedules the recurring maintenance tasks. Called once from onEnable AFTER TagService exists. */
    public void startTasks() {
        // expiry sweep: transitions happen inside the synchronized manager, the per-request
        // consequences (refund, notify, webhook) run through TagService on the main thread
        expiryTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (expireAfterMillis <= 0) return;
            for (TagRequest expired : requests.expireSweep(expireAfterMillis)) {
                plugin.tagService().handleExpiredRequest(expired);
            }
        }, expireCheckTicks, expireCheckTicks).getTaskId();

        // audit + token-ledger retention: once shortly after startup, then daily
        retentionTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            audit.runRetentionSweep();
            tokens.runRetentionSweep();
        }, 20L * 60, 20L * 60 * 60 * 24).getTaskId();
    }

    // ---- module settings application ----

    private void applyNetworkSettings() {
        YamlConfiguration net = configs.config("network", "settings.yml");
        this.serverName = net.getString("server-name", "server");
    }

    /** Builds the sync backend selected in network/settings.yml. Falls back to standalone on error. */
    private SyncService createSyncService() {
        YamlConfiguration net = configs.config("network", "settings.yml");
        String backend = net.getString("sync-backend", "none").toLowerCase(Locale.ROOT).trim();
        if (backend.equals("redis")) {
            try {
                RedisSyncService redis = new RedisSyncService(
                        net.getString("redis.host", "localhost"),
                        net.getInt("redis.port", 6379),
                        net.getString("redis.password", ""),
                        net.getString("redis.channel", "customtag"),
                        serverName, plugin.getLogger(), mainThread);
                redis.onEvent(this::handleRemoteEvent);
                return redis;
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[CustomTag] Could not initialize the Redis sync "
                        + "backend - running as a standalone server. Check network/settings.yml.", ex);
            }
        }
        return new NoopSyncService();
    }

    /** Tears down the previous sync backend and connects the newly-configured one (network reload). */
    private void rebuildSyncService() {
        SyncService previous = sync;
        // Detach the old backend's handler first. The old and new backend subscribe to the same
        // channel, so for as long as both are live every event would be handled twice; clearing
        // the handler makes the old one inert immediately, before the new one exists.
        previous.onEvent(null);
        sync = createSyncService();
        // Then close it off the main thread: tearing a Redis backend down unsubscribes a socket,
        // drains an executor with a 3-second timeout and closes a connection pool. Doing that
        // inline froze the entire server for that window on every /customtag reload network.
        guardedIo.execute(previous::shutdown);
        plugin.getLogger().info("[CustomTag] Sync backend: " + sync.name());
    }

    /**
     * Everything an incoming cross-server event needs on THIS server: re-read the touched
     * player's row from the shared database, then - if that player happens to be online here -
     * hand them any notification the other server queued for them ("your tag was approved",
     * ...), so a player reviewing on lobby-3 gets told even though staff clicked on lobby-1.
     *
     * The re-read is delayed one second: the origin server persists asynchronously too, and
     * re-reading a shared database faster than the writer commits would only fetch stale rows.
     */
    private void handleRemoteEvent(SyncEvent event) {
        String raw = event.data() == null ? null : event.data().get("player");
        if (raw == null) return;
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.data().refreshFromBackend(uuid, () -> deliverQueuedNotice(uuid)), 20L);
    }

    /** Same delivery rules as PlayerJoinListener, but triggered by a sync event instead of a join. */
    private void deliverQueuedNotice(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online == null) return;
        PlayerData data = plugin.data().get(uuid, online.getName());
        if (data.getPendingNotice() == null) return;
        if (data.isPendingNoticeResume()) {
            online.sendMessage(plugin.tagService().buildResumeMessage(data.getPendingNotice()));
        } else {
            online.sendMessage(ColorUtil.parse(data.getPendingNotice()));
        }
        data.setPendingNotice(null);
        data.setPendingNoticeResume(false);
        plugin.data().save(data);
    }

    /**
     * Tells every other server in the network that this player's stored data changed and should
     * be re-read from the shared database. Cheap no-op on a standalone server.
     */
    public void notifyPlayerDataChanged(UUID player) {
        sync.publish(new SyncEvent("player-data-changed", serverName,
                Map.of("player", player.toString())));
    }

    private void applyQueueSettings() {
        YamlConfiguration q = configs.config("queue", "settings.yml");
        requests.setGlobalPendingLimit(q.getInt("global-pending-limit", 50));
        requests.setLockTimeoutMillis(q.getLong("lock-timeout-minutes", 5) * 60_000L);
        requests.setClosedHistoryCap(q.getInt("closed-history-cap", 500));
        this.expireAfterMillis = q.getLong("expire-after-days", 7) * 24L * 60 * 60 * 1000;
        this.expireCheckTicks = Math.max(20L * 60, q.getLong("expire-check-minutes", 30) * 60 * 20);
    }

    private void applySecuritySettings() {
        YamlConfiguration s = configs.config("security", "settings.yml");
        security.configure(
                Math.max(1, s.getInt("submissions.max-per-window", 5)),
                Math.max(1, s.getLong("submissions.window-seconds", 60)) * 1000L,
                Math.max(0, s.getLong("submissions.duplicate-window-minutes", 10)) * 60_000L);
    }

    private void applyTokenSettings() {
        YamlConfiguration t = configs.config("tokens", "settings.yml");
        this.refundStaffReject = t.getBoolean("refunds.staff-reject", true);
        this.refundAiReject = t.getBoolean("refunds.ai-reject", true);
        this.refundQueueExpired = t.getBoolean("refunds.queue-expired", true);
    }

    private void applyLogSettings() {
        YamlConfiguration l = configs.config("logs", "settings.yml");
        int days = l.getInt("retention-days", 90);
        audit.setRetentionDays(days);
        // Same window as the audit trail: the token ledger is monthly files under logs/tokens/,
        // and without a sweep it grew forever on long-lived production servers.
        tokens.setRetentionDays(days);
    }

    private void applyWebhookSettings() {
        WebhookConfigLoader.Loaded loaded = WebhookConfigLoader.load(configs, plugin.getLogger());
        webhooks.configure(loaded.endpoints(), loaded.templates(),
                loaded.maxAttempts(), loaded.retryDelaySeconds(), serverName);
    }

    /**
     * Hot-reloads one module by folder name ({@code /customtag reload <module>}).
     * Returns false for an unknown module name.
     */
    public boolean reloadModule(String module) {
        switch (module.toLowerCase(Locale.ROOT)) {
            case "blacklist" -> validation.reload();
            case "permissions" -> permissions.reload();
            case "ai" -> AIConfigLoader.apply(ai, configs, http, plugin.getLogger());
            case "webhooks" -> {
                applyWebhookSettings();
            }
            case "queue" -> {
                configs.reloadModule("queue");
                applyQueueSettings();
            }
            case "security" -> {
                configs.reloadModule("security");
                applySecuritySettings();
            }
            case "tokens" -> {
                configs.reloadModule("tokens");
                applyTokenSettings();
            }
            case "logs" -> {
                configs.reloadModule("logs");
                applyLogSettings();
            }
            case "network" -> {
                configs.reloadModule("network");
                applyNetworkSettings();
                applyWebhookSettings(); // webhook payloads embed the server name
                rebuildSyncService();
            }
            default -> {
                return false;
            }
        }
        audit.log(AuditCategory.SYSTEM, "reload", "staff", null, null, null, "module: " + module);
        return true;
    }

    /** Full platform reload - every module, in dependency-safe order. */
    public void reloadAll() {
        configs.reloadAll();
        applyNetworkSettings();
        applyQueueSettings();
        applySecuritySettings();
        applyTokenSettings();
        applyLogSettings();
        validation.reload();
        permissions.reload();
        AIConfigLoader.apply(ai, configs, http, plugin.getLogger());
        applyWebhookSettings();
        audit.log(AuditCategory.SYSTEM, "reload", "staff", null, null, null, "module: all");
    }

    // ---- shared cross-service helpers ----

    /**
     * THE way to change a token balance anywhere in the plugin: applies the transaction through
     * the token service, and on success logs it to the audit trail, fires
     * {@link TokenBalanceChangeEvent}, publishes the {@code TOKEN_TRANSACTION} webhook and the
     * cross-server sync event. Must be called on the main thread (it mutates live PlayerData
     * and fires a Bukkit event).
     */
    public TokenService.Result applyTokens(PlayerData data, TokenTransactionType type, int amount,
                                           String reason, String actorName) {
        TokenService.Result result = tokens.apply(data, playerIds.idFor(data.getUuid()),
                type, amount, reason, actorName);
        if (result instanceof TokenService.Result.Success success) {
            TokenTransaction tx = success.transaction();
            audit.log(AuditCategory.TOKEN, type.name().toLowerCase(Locale.ROOT), actorName,
                    data.getLastKnownName(), playerIds.display(data.getUuid()), null,
                    tx.transactionId() + " amount=" + tx.amount() + " balance=" + tx.balanceAfter()
                            + " reason=" + reason);
            Bukkit.getPluginManager().callEvent(new TokenBalanceChangeEvent(tx));
            Map<String, String> payload = new HashMap<>();
            payload.put("transaction-id", tx.transactionId());
            payload.put("player", tx.playerName());
            payload.put("custom-id", tx.playerCustomId());
            payload.put("type", tx.type().name());
            payload.put("amount", String.valueOf(tx.amount()));
            payload.put("balance", String.valueOf(tx.balanceAfter()));
            payload.put("reason", reason == null ? "-" : reason);
            payload.put("actor", actorName == null ? "-" : actorName);
            webhooks.publish(WebhookEventType.TOKEN_TRANSACTION, payload);
            notifyPlayerDataChanged(data.getUuid());
        }
        return result;
    }

    /** The standard {placeholder} payload for request-related webhooks and templates. */
    public Map<String, String> requestPayload(TagRequest request) {
        Map<String, String> data = new HashMap<>();
        data.put("request-id", request.getRequestId());
        data.put("player", request.getPlayerName());
        data.put("custom-id", request.getPlayerCustomId());
        data.put("tag", request.getPlainText());
        data.put("status", request.getStatus().name());
        data.put("priority", String.valueOf(request.getPriority()));
        data.put("server", request.getServerName());
        if (request.getRejectReason() != null) data.put("reason", request.getRejectReason());
        if (request.getAiProvider() != null) {
            data.put("ai-provider", request.getAiProvider());
            data.put("ai-confidence", String.valueOf(request.getAiConfidence()));
            data.put("ai-reason", request.getAiReason() == null ? "-" : request.getAiReason());
        }
        return data;
    }

    /**
     * Review priority of one role: queue/settings.yml {@code priorities.<role>} wins, falling
     * back to the role's own {@code queue-priority} from permissions/roles.yml.
     */
    public int priorityFor(RoleDefinition role) {
        YamlConfiguration q = configs.config("queue", "settings.yml");
        return q.getInt("priorities." + role.name(), role.queuePriority());
    }

    /**
     * Persists a live change of the global pending limit back to queue/settings.yml so it
     * survives restarts ({@code /customtag pending <n>}).
     */
    public void setGlobalPendingLimit(int limit) {
        requests.setGlobalPendingLimit(limit);
        File file = new File(new File(plugin.getDataFolder(), "queue"), "settings.yml");
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            cfg.set("global-pending-limit", limit);
            cfg.save(file);
            configs.reloadModule("queue");
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "[CustomTag] Could not persist the new pending limit to queue/settings.yml", ex);
        }
    }

    // ---- getters ----

    public ModuleConfigService configs() { return configs; }
    public PersistentCounters counters() { return counters; }
    public PlayerIdService playerIds() { return playerIds; }
    public RequestManager requests() { return requests; }
    public ValidationService validation() { return validation; }
    public TokenService tokens() { return tokens; }
    public SecurityService security() { return security; }
    public AuditLogService audit() { return audit; }
    public AIModerationService ai() { return ai; }
    public WebhookService webhooks() { return webhooks; }
    public PermissionService permissions() { return permissions; }
    public SyncService sync() { return sync; }
    public String serverName() { return serverName; }
    public boolean refundOnStaffReject() { return refundStaffReject; }
    public boolean refundOnAiReject() { return refundAiReject; }
    public boolean refundOnQueueExpired() { return refundQueueExpired; }

    /** Orderly shutdown: cancel tasks, stop async services, drain IO, final synchronous flush. */
    public void shutdown() {
        if (expiryTaskId != -1) Bukkit.getScheduler().cancelTask(expiryTaskId);
        if (retentionTaskId != -1) Bukkit.getScheduler().cancelTask(retentionTaskId);

        audit.log(AuditCategory.SYSTEM, "shutdown", "system", null, null, null, "platform disabling");
        ai.shutdown();
        webhooks.shutdown();
        sync.shutdown();

        // drain queued async writes first, then do one final synchronous flush of everything -
        // same "no stale write may land after the final save" ordering DataManager#shutdown uses
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
        requests.flushNow();
        counters.flushNow();
        playerIds.flushNow();
        // freezes were previously only ever written fire-and-forget on the executor that was just
        // drained above, so a suspension applied in the final seconds before a stop could vanish
        tokens.flushNow();

        CustomTagAPI.register(null);
    }
}
