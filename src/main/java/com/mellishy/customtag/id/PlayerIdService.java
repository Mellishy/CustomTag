package com.mellishy.customtag.id;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mellishy.customtag.util.JsonFiles;
import com.mellishy.customtag.util.PersistentCounters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Permanent, human-friendly per-player IDs like {@code <#3VF-2>}:
 * short enough for staff to read out loud or search in Discord, unique forever, stored against
 * the player's UUID, and shown in every queue entry, log line and webhook payload.
 *
 * IDs are derived from a durable counter encoded in Crockford base-32 (no 0/O or 1/I/L
 * ambiguity), so they are collision-free by construction and survive restarts via
 * {@link PersistentCounters}. Once assigned, an ID is never changed or reused.
 *
 * THREAD SAFETY: fully thread safe (concurrent map + atomic counter); persistence is an
 * async atomic file write serialized on the shared platform IO executor.
 */
public class PlayerIdService {

    private static final Gson GSON = new Gson();
    /** Crockford base-32 alphabet - unambiguous when read by humans. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Path file;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final PersistentCounters counters;
    private final Map<UUID, String> ids = new ConcurrentHashMap<>();
    private final Map<String, UUID> reverse = new ConcurrentHashMap<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    /**
     * Set when {@link #load()} could not fully read the existing mapping. The in-memory map is then
     * missing ids that really are assigned on disk, so writing it back would erase them for good -
     * and an id, once shown to staff, must never point at a different player.
     */
    private volatile boolean loadFailed;

    public PlayerIdService(Path file, PersistentCounters counters, Executor ioExecutor, Consumer<Exception> errorReporter) {
        this.file = file;
        this.counters = counters;
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
            errorReporter.accept(new IOException("Could not read " + file.getFileName() + " - every player will "
                    + "be assigned a NEW custom id this session, and the existing mapping will NOT be overwritten. "
                    + "Fix the file/permissions and restart.", ex));
            return;
        }
        if (json == null || json.isBlank()) return;
        try {
            Map<String, String> raw = GSON.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (raw == null) return;
            // Per-entry try/catch, not one around the whole loop: a single corrupted key used to
            // abort the load halfway through, so every player after it in the file kept their id on
            // disk but got a brand-new one issued in memory - two live ids for one account, and the
            // flush below would then persist the wrong one.
            raw.forEach((uuid, id) -> {
                if (id == null || id.isBlank()) return;
                UUID parsed;
                try {
                    parsed = UUID.fromString(uuid);
                } catch (IllegalArgumentException ex) {
                    loadFailed = true;
                    errorReporter.accept(new IllegalArgumentException("Skipping unreadable player id entry '"
                            + uuid + "' in " + file.getFileName() + "; that player will be issued a new id.", ex));
                    return;
                }
                ids.put(parsed, id);
                reverse.put(id, parsed);
            });
        } catch (Exception ex) {
            loadFailed = true;
            errorReporter.accept(ex);
        }
    }

    /** Returns this player's permanent custom ID, assigning one on first use. */
    public String idFor(UUID uuid) {
        String existing = ids.get(uuid);
        if (existing != null) return existing;
        // computeIfAbsent would be racy with the counter side effect if two threads asked at once;
        // synchronize the slow first-assignment path only (subsequent reads never enter here)
        synchronized (this) {
            existing = ids.get(uuid);
            if (existing != null) return existing;
            String id = format(counters.next("player-id"));
            ids.put(uuid, id);
            reverse.put(id, uuid);
            queueFlush();
            return id;
        }
    }

    /** Reverse lookup used by staff search - accepts both "3VF-2" and the displayed "<#3VF-2>" form. */
    public Optional<UUID> byId(String customId) {
        if (customId == null) return Optional.empty();
        String cleaned = customId.trim().toUpperCase(java.util.Locale.ROOT)
                .replace("<", "").replace(">", "").replace("#", "");
        return Optional.ofNullable(reverse.get(cleaned));
    }

    /** The displayed form used across menus, logs and webhooks: {@code <#3VF-2>}. */
    public String display(UUID uuid) {
        return "<#" + idFor(uuid) + ">";
    }

    /**
     * Encodes a counter value as {@code XXX-Y}: the last base-32 digit is split off after a dash,
     * which keeps IDs visually distinct at a glance ("3VF-2" vs "3VF2") and matches the display
     * format used everywhere in the plugin.
     */
    static String format(long counterValue) {
        StringBuilder encoded = new StringBuilder();
        long v = counterValue;
        do {
            encoded.insert(0, ALPHABET[(int) (v % 32)]);
            v /= 32;
        } while (v > 0);
        while (encoded.length() < 4) {
            encoded.insert(0, '0');
        }
        String s = encoded.toString();
        return s.substring(0, s.length() - 1) + "-" + s.charAt(s.length() - 1);
    }

    private void queueFlush() {
        if (flushQueued.compareAndSet(false, true)) {
            ioExecutor.execute(() -> {
                flushQueued.set(false);
                flushNow();
            });
        }
    }

    /** Synchronous write - called from the async flush and once more on plugin shutdown. */
    public void flushNow() {
        if (loadFailed) return; // see the loadFailed field - writing now would erase real assignments
        try {
            Map<String, String> snapshot = new java.util.HashMap<>();
            ids.forEach((uuid, id) -> snapshot.put(uuid.toString(), id));
            JsonFiles.writeAtomic(file, GSON.toJson(snapshot));
        } catch (IOException ex) {
            errorReporter.accept(ex);
        }
    }
}
