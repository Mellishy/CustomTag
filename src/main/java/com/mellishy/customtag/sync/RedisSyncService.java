package com.mellishy.customtag.sync;

import com.google.gson.Gson;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;

/**
 * Redis pub/sub bridge between the servers of a network. Every server publishes its
 * {@link SyncEvent}s to one shared channel and receives everyone else's; events that carry our
 * own server-name are dropped, so a server never reacts to its own broadcasts.
 *
 * Connection handling:
 * - the SUBSCRIBE connection is a dedicated long-lived socket (Redis requires that - a
 *   subscribing connection can't run other commands) owned by a single daemon thread. If Redis
 *   goes down, that thread keeps retrying with a fixed back-off and picks the stream back up as
 *   soon as the broker returns - no restart needed, no exception spam (errors are throttled).
 * - PUBLISH goes through a small connection pool on a separate single-threaded executor, so
 *   {@link #publish} never blocks the caller (it may be invoked from the main server thread).
 *
 * Incoming events are handed to the registered handler on the main thread via the injected
 * executor, keeping the same threading contract as the rest of the plugin.
 */
public class RedisSyncService implements SyncService {

    private static final Gson GSON = new Gson();
    private static final long RECONNECT_DELAY_MILLIS = 5_000;
    private static final long ERROR_LOG_THROTTLE_MILLIS = 30_000;

    private final String host;
    private final int port;
    private final String password;
    private final String channel;
    private final String serverName;
    private final Logger logger;
    private final Executor mainThread;

    private final JedisPool publishPool;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CustomTag-Redis-Publish");
        t.setDaemon(true);
        return t;
    });
    private final Thread subscriberThread;

    private volatile Consumer<SyncEvent> handler;
    private volatile JedisPubSub activeSubscription;
    private volatile boolean closed;
    private volatile long lastErrorLogAt;
    private volatile boolean everConnected;

    public RedisSyncService(String host, int port, String password, String channel,
                            String serverName, Logger logger, Executor mainThread) {
        this.host = host;
        this.port = port;
        this.password = password == null || password.isBlank() ? null : password;
        this.channel = channel;
        this.serverName = serverName;
        this.logger = logger;
        this.mainThread = mainThread;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(2);
        this.publishPool = new JedisPool(poolConfig, host, port, 2_000, this.password);

        this.subscriberThread = new Thread(this::runSubscriberLoop, "CustomTag-Redis-Subscribe");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    @Override
    public void publish(SyncEvent event) {
        if (closed || event == null) return;
        String json = GSON.toJson(event);
        publishExecutor.execute(() -> {
            try (Jedis jedis = publishPool.getResource()) {
                jedis.publish(channel, json);
            } catch (Exception ex) {
                logThrottled("Could not publish a sync event to Redis (" + ex.getMessage()
                        + ") - the other servers will re-read this player's data on their next lookup.");
            }
        });
    }

    @Override
    public void onEvent(Consumer<SyncEvent> handler) {
        this.handler = handler;
    }

    private void runSubscriberLoop() {
        while (!closed) {
            // socketTimeoutMillis 0 = block forever waiting for messages; SUBSCRIBE is exactly
            // the one place where an infinite read timeout is correct
            DefaultJedisClientConfig config = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(5_000)
                    .socketTimeoutMillis(0)
                    .password(password)
                    .build();
            try (Jedis jedis = new Jedis(new HostAndPort(host, port), config)) {
                JedisPubSub subscription = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        dispatch(message);
                    }
                };
                this.activeSubscription = subscription;
                if (!everConnected) {
                    everConnected = true;
                    logger.info("[CustomTag] Connected to Redis at " + host + ":" + port
                            + " - cross-server sync active on channel '" + channel + "'.");
                } else {
                    logger.info("[CustomTag] Redis connection re-established - cross-server sync resumed.");
                }
                jedis.subscribe(subscription, channel); // blocks until unsubscribe/connection loss
            } catch (Exception ex) {
                if (closed) break;
                logThrottled("Redis connection lost/unavailable (" + ex.getMessage() + ") - retrying every "
                        + (RECONNECT_DELAY_MILLIS / 1000) + "s. The plugin keeps working normally; only "
                        + "cross-server sync is paused until Redis is back.");
            } finally {
                this.activeSubscription = null;
            }
            if (closed) break;
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void dispatch(String json) {
        Optional<SyncEvent> parsed = SyncEvent.parseIncoming(json, serverName);
        if (parsed.isEmpty()) {
            if (SyncEvent.isMalformedPayload(json)) {
                logThrottled("Ignored a malformed message on the '" + channel + "' Redis channel.");
            }
            return;
        }
        Consumer<SyncEvent> current = handler;
        if (current != null) {
            SyncEvent event = parsed.get();
            mainThread.execute(() -> current.accept(event));
        }
    }

    /** At most one warning per 30s - a downed Redis must never flood the console. */
    private void logThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt < ERROR_LOG_THROTTLE_MILLIS) return;
        lastErrorLogAt = now;
        logger.log(Level.WARNING, "[CustomTag] " + message);
    }

    @Override
    public void shutdown() {
        closed = true;
        JedisPubSub subscription = activeSubscription;
        if (subscription != null) {
            try {
                subscription.unsubscribe();
            } catch (Exception ignored) {
                // the connection may already be gone - the daemon thread exits either way
            }
        }
        subscriberThread.interrupt();
        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                publishExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            publishExecutor.shutdownNow();
        }
        publishPool.close();
    }

    @Override
    public String name() {
        return "redis (" + host + ":" + port + ", channel '" + channel + "')";
    }
}
