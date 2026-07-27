package com.mellishy.customtag.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small sliding-window rate limiter keyed by arbitrary strings (player uuid, endpoint name,
 * "global", ...). Used by the security service for submission spam and by the webhook/AI
 * services for outbound request limits.
 *
 * THREAD SAFETY: per-key windows are synchronized on their own deque; the key map is concurrent.
 * BUKKIT-FREE: clock injected, fully unit-testable.
 */
public class RateLimiter {

    /** Only sweep idle keys once the map is big enough for the walk to be worth it. */
    private static final int PRUNE_THRESHOLD = 256;

    private final int maxEvents;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxEvents, long windowMillis, LongSupplier clock) {
        this.maxEvents = maxEvents;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * Records one event for {@code key} and reports whether it is within the limit.
     * A limit of {@code <= 0} means unlimited (always allowed, nothing recorded).
     */
    public boolean tryAcquire(String key) {
        if (maxEvents <= 0) return true;
        long now = clock.getAsLong();
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (window) {
            long cutoff = now - windowMillis;
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.removeFirst();
            }
            allowed = window.size() < maxEvents;
            if (allowed) window.addLast(now);
        }
        pruneIdleWindows(now);
        return allowed;
    }

    /**
     * Drops windows whose events have all aged out. Without this the map grew one permanent entry
     * per key forever - and the submission limiter is keyed by player UUID, so on a long-running
     * server that is one entry per unique player who ever submitted a tag, none of which is ever
     * read again. Keys are unbounded in count but individually tiny, so the sweep only runs once
     * the map is big enough to be worth walking rather than on every single acquire.
     */
    private void pruneIdleWindows(long now) {
        if (windows.size() < PRUNE_THRESHOLD) return;
        long cutoff = now - windowMillis;
        windows.values().removeIf(w -> {
            synchronized (w) {
                // peekLast, not peekFirst: only a window whose NEWEST event has aged out is
                // genuinely idle and safe to forget.
                Long newest = w.peekLast();
                return newest == null || newest < cutoff;
            }
        });
    }

    /** Seconds until the oldest event leaves the window (how long the caller should wait). */
    public long retryAfterSeconds(String key) {
        Deque<Long> window = windows.get(key);
        if (window == null) return 0;
        synchronized (window) {
            Long oldest = window.peekFirst();
            if (oldest == null) return 0;
            long readyAt = oldest + windowMillis;
            return Math.max(0, (readyAt - clock.getAsLong() + 999) / 1000);
        }
    }

    /** Drops every window - used on reload so new limits apply cleanly. */
    public void clear() {
        windows.clear();
    }
}
