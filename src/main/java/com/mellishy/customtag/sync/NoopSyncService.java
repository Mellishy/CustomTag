package com.mellishy.customtag.sync;

import java.util.function.Consumer;

/**
 * Single-server implementation: publishing is a no-op and no remote events ever arrive. This is
 * the correct behaviour on a standalone server - and the reason the rest of the codebase can
 * unconditionally call {@code sync.publish(...)} without caring whether a network is configured.
 */
public class NoopSyncService implements SyncService {

    @Override
    public void publish(SyncEvent event) {
        // single server - nobody to tell
    }

    @Override
    public void onEvent(Consumer<SyncEvent> handler) {
        // no remote events will ever arrive
    }

    @Override
    public void shutdown() {
    }

    @Override
    public String name() {
        return "none (single server)";
    }
}
