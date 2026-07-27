package com.mellishy.customtag.sync;

import java.util.function.Consumer;

/**
 * Cross-server synchronization. The whole plugin publishes its network-relevant events through
 * this interface and never cares which backend carries them: {@link NoopSyncService} on a
 * standalone server, {@link RedisSyncService} when network/settings.yml selects redis. Adding
 * another broker (RabbitMQ, plugin messaging, ...) only means implementing these four methods -
 * nothing else in the codebase changes.
 *
 * Implementations must be fully async: {@link #publish} may be called from the main thread and
 * must never block on network I/O.
 */
public interface SyncService {

    /** Broadcasts one event to every other server in the network. Must not block. */
    void publish(SyncEvent event);

    /**
     * Registers the handler invoked (on the main thread) for events from OTHER servers.
     * A {@code null} handler detaches the current one, making this backend inert - used when a
     * reload swaps backends, so the outgoing one stops dispatching before the incoming one
     * subscribes to the same channel.
     */
    void onEvent(Consumer<SyncEvent> handler);

    /** Releases connections/threads - called from plugin shutdown. */
    void shutdown();

    /** Human-readable backend name for startup logs ("none", "redis", ...). */
    String name();
}
