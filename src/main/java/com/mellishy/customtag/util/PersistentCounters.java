package com.mellishy.customtag.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Durable, monotonically increasing counters backing every human-readable ID system in the
 * plugin: request ids ({@code REQ-00000001}), token transaction ids ({@code TOKEN-00000001}),
 * and player custom ids. Uniqueness and non-reuse survive restarts because the highest issued
 * value is persisted; a crash between increment and flush can only ever SKIP numbers, never
 * hand the same number out twice (the in-memory counter is the source of truth while running,
 * and it starts from the persisted high-water mark).
 *
 * THREAD SAFETY: fully thread safe - counters are {@link AtomicLong}s in a concurrent map and
 * flushing is serialized on the supplied executor.
 */
public class PersistentCounters {

    private static final Gson GSON = new Gson();

    private final Path file;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    /**
     * Set when {@link #load()} could not read an existing counters file. Every counter is then
     * starting from zero purely because we couldn't see the real high-water mark - flushing that
     * would overwrite the only record of it and permanently re-issue ids that are already in use.
     */
    private volatile boolean loadFailed;

    public PersistentCounters(Path file, Executor ioExecutor, Consumer<Exception> errorReporter) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.errorReporter = errorReporter;
        load();
    }

    private void load() {
        String json;
        try {
            json = JsonFiles.read(file);
        } catch (IOException ex) {
            loadFailed = true;
            errorReporter.accept(new IOException("Could not read " + file.getFileName() + " - id counters are "
                    + "starting from zero for this session and will NOT be saved, so already-issued ids are "
                    + "not overwritten. Fix the file/permissions and restart.", ex));
            return;
        }
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Long> raw = GSON.fromJson(json, new TypeToken<Map<String, Long>>() {}.getType());
            if (raw != null) {
                // Skip null values rather than letting AtomicLong's auto-unboxing NPE: a hand-edited
                // or partially-written file can legitimately contain `"request": null`, and one such
                // entry used to abort the whole load - leaving every OTHER counter at zero too.
                raw.forEach((k, v) -> {
                    if (v != null) counters.put(k, new AtomicLong(v));
                });
            }
        } catch (Exception ex) {
            loadFailed = true;
            errorReporter.accept(ex);
        }
    }

    /** Returns the next value for {@code name} (first call returns 1) and queues a flush. */
    public long next(String name) {
        long value = counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
        queueFlush();
        return value;
    }

    /** Current value without incrementing (0 when never used). */
    public long current(String name) {
        AtomicLong c = counters.get(name);
        return c != null ? c.get() : 0;
    }

    private void queueFlush() {
        // collapse bursts of increments into one write - correctness doesn't depend on every
        // individual flush landing (see class javadoc), so losing an intermediate write is fine
        if (flushQueued.compareAndSet(false, true)) {
            ioExecutor.execute(() -> {
                flushQueued.set(false);
                flushNow();
            });
        }
    }

    /** Synchronous write of the current values - also called on plugin shutdown. */
    public void flushNow() {
        if (loadFailed) return; // see the loadFailed field - writing now would destroy the real values
        try {
            Map<String, Long> snapshot = new java.util.HashMap<>();
            counters.forEach((k, v) -> snapshot.put(k, v.get()));
            JsonFiles.writeAtomic(file, GSON.toJson(snapshot));
        } catch (IOException ex) {
            errorReporter.accept(ex);
        }
    }
}
