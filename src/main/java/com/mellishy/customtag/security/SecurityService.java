package com.mellishy.customtag.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mellishy.customtag.util.JsonFiles;
import com.mellishy.customtag.validation.TextNormalizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Central anti-abuse / anti-duplication layer. Sits in front of every
 * submission and sensitive operation:
 *
 * <ul>
 *   <li><b>Submission rate limit</b> - spamming invalid tags costs nothing but burns staff/AI
 *       time; a sliding window per player stops it cold.</li>
 *   <li><b>Duplicate protection</b> - the same player re-submitting the same (normalized) text
 *       within a short window is swallowed instead of creating a second request.</li>
 *   <li><b>Player operation lock</b> - a re-entrancy guard so one player can never have two
 *       submission flows mutating their data at the same time (double-click, packet spam).</li>
 *   <li><b>Security flags</b> - persistent per-player counters (spam attempts, blacklist hits,
 *       unicode abuse, ...) staff can inspect; heavy offenders surface immediately.</li>
 *   <li><b>Maintenance mode</b> - individual subsystems (submissions, ai, webhooks) can be
 *       frozen without touching the rest of the plugin (emergency protection).</li>
 * </ul>
 *
 * BUKKIT-FREE and unit-testable; the clock and IO executor are injected.
 */
public class SecurityService {

    private static final Gson GSON = new Gson();

    private final Path flagsFile;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final LongSupplier clock;

    private volatile RateLimiter submissionLimiter;
    private volatile long duplicateWindowMillis = 10 * 60_000L;

    /** Player uuids currently inside a submission-critical section (re-entrancy guard). */
    private final Set<UUID> operationLocks = ConcurrentHashMap.newKeySet();
    /** (uuid + normalized text) -> last submission time, for duplicate swallowing. */
    private final Map<String, Long> recentSubmissions = new ConcurrentHashMap<>();
    /** Persistent per-player security flag counters: uuid -> (flag type -> count). */
    private final Map<UUID, Map<String, Integer>> flags = new ConcurrentHashMap<>();
    /** Subsystems currently frozen by staff or emergency protection. */
    private final Set<String> maintenance = ConcurrentHashMap.newKeySet();
    /** Collapses a burst of flag() calls into one flag-file write - see {@link #saveFlags()}. */
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    /**
     * Set when {@link #loadFlags()} could not fully read the existing flag file. The in-memory map
     * is then missing history that really is on disk, so writing it back would erase the record of
     * players staff are actively watching.
     */
    private volatile boolean loadFailed;

    public SecurityService(Path flagsFile, Executor ioExecutor, Consumer<Exception> errorReporter,
                           LongSupplier clock) {
        this.flagsFile = flagsFile;
        this.ioExecutor = ioExecutor;
        this.errorReporter = errorReporter;
        this.clock = clock;
        this.submissionLimiter = new RateLimiter(5, 60_000L, clock);
        loadFlags();
    }

    /** Applies limits from security/settings.yml (called at startup and on module reload). */
    public void configure(int maxSubmissionsPerWindow, long windowMillis, long duplicateWindowMillis) {
        this.submissionLimiter = new RateLimiter(maxSubmissionsPerWindow, windowMillis, clock);
        this.duplicateWindowMillis = duplicateWindowMillis;
    }

    // ---- submission gate ----

    /** Why a submission was blocked at the security layer. */
    public enum Block { NONE, RATE_LIMITED, DUPLICATE, MAINTENANCE }

    /**
     * The security check run before any validation/token/queue work. Records the attempt, so
     * even rejected submissions count toward the rate limit (spamming invalid tags is exactly
     * the abuse case).
     */
    public Block checkSubmission(UUID player, String rawText) {
        if (maintenance.contains("submissions")) return Block.MAINTENANCE;
        if (!submissionLimiter.tryAcquire(player.toString())) {
            flag(player, "rate-limit");
            return Block.RATE_LIMITED;
        }
        String key = player + ":" + TextNormalizer.normalize(rawText);
        long now = clock.getAsLong();
        pruneRecent(now);
        Long last = recentSubmissions.put(key, now);
        if (last != null && now - last < duplicateWindowMillis) {
            flag(player, "duplicate-submission");
            return Block.DUPLICATE;
        }
        return Block.NONE;
    }

    public long submissionRetryAfterSeconds(UUID player) {
        return submissionLimiter.retryAfterSeconds(player.toString());
    }

    private void pruneRecent(long now) {
        // bounded, self-cleaning map: entries older than the duplicate window are useless
        if (recentSubmissions.size() < 1000) return;
        recentSubmissions.entrySet().removeIf(e -> now - e.getValue() > duplicateWindowMillis);
    }

    // ---- per-player operation lock (re-entrancy guard) ----

    /** Enters the submission-critical section for this player; false when already inside one. */
    public boolean tryAcquireOperationLock(UUID player) {
        return operationLocks.add(player);
    }

    public void releaseOperationLock(UUID player) {
        operationLocks.remove(player);
    }

    // ---- security flags ----

    /** Increments a persistent flag counter for this player (e.g. "blacklist-hit", "rate-limit"). */
    public void flag(UUID player, String type) {
        flags.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .merge(type, 1, Integer::sum);
        saveFlags();
    }

    /** Immutable view of a player's flag counters (empty when clean). */
    public Map<String, Integer> flagsOf(UUID player) {
        Map<String, Integer> f = flags.get(player);
        return f == null ? Map.of() : Map.copyOf(f);
    }

    /** Total flags across all types - a quick "how suspicious is this player" number. */
    public int totalFlags(UUID player) {
        return flagsOf(player).values().stream().mapToInt(Integer::intValue).sum();
    }

    // ---- maintenance mode ----

    /** Freezes one subsystem ("submissions", "ai", "webhooks"). Returns false when already frozen. */
    public boolean enableMaintenance(String subsystem) {
        return maintenance.add(normalizeSubsystem(subsystem));
    }

    public boolean disableMaintenance(String subsystem) {
        return maintenance.remove(normalizeSubsystem(subsystem));
    }

    public boolean isUnderMaintenance(String subsystem) {
        return maintenance.contains(normalizeSubsystem(subsystem));
    }

    /**
     * Locale.ROOT, not the default locale: these keys are protocol identifiers, not display text.
     * On a server running under a Turkish locale the default-locale fold turns "AI" into "aı",
     * so /customtag maintenance ai on would store a key that isUnderMaintenance("ai") could never
     * match again - the subsystem would report healthy while actually being frozen.
     */
    private static String normalizeSubsystem(String subsystem) {
        return subsystem == null ? "" : subsystem.trim().toLowerCase(Locale.ROOT);
    }

    public Set<String> activeMaintenance() {
        return Set.copyOf(maintenance);
    }

    // ---- persistence ----

    private void loadFlags() {
        String json;
        try {
            json = JsonFiles.read(flagsFile);
        } catch (IOException ex) {
            loadFailed = true;
            errorReporter.accept(new IOException("Could not read " + flagsFile.getFileName()
                    + " - every player's abuse-flag history starts empty this session. The file will not be "
                    + "overwritten, so the history survives a restart once the problem is fixed.", ex));
            return;
        }
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Map<String, Integer>> raw = GSON.fromJson(json,
                    new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
            if (raw == null) return;
            // Per-entry, not one try/catch around the whole loop: a single corrupt uuid used to
            // abort the load and silently clear every remaining player's flag history.
            raw.forEach((uuid, counters) -> {
                if (counters == null) return;
                try {
                    flags.put(UUID.fromString(uuid), new ConcurrentHashMap<>(counters));
                } catch (IllegalArgumentException | NullPointerException ex) {
                    loadFailed = true;
                    errorReporter.accept(new IllegalArgumentException("Skipping unreadable security-flag entry '"
                            + uuid + "' in " + flagsFile.getFileName() + ".", ex));
                }
            });
        } catch (Exception ex) {
            loadFailed = true;
            errorReporter.accept(ex);
        }
    }

    /**
     * Queues a flush of the whole flag map, collapsing bursts into a single write.
     *
     * flag() is called on the hot abuse path - once per rate-limited submission, once per
     * blacklist hit - which is exactly when a player is spamming as fast as the server will
     * accept packets. Snapshotting and re-serialising every flagged player on the server for each
     * of those attempts turned an attacker's spam into O(flagged players) of main-thread copying
     * plus one full-file disk write per packet: the anti-abuse layer was itself the amplifier.
     * Coalescing (the same trick {@link com.mellishy.customtag.util.PersistentCounters} uses)
     * caps that at one write in flight, and the snapshot is now taken on the I/O thread - the
     * concurrent maps are safe to iterate there, and losing an intermediate write is harmless
     * because the next flush always persists the latest counts.
     */
    private void saveFlags() {
        if (loadFailed) return; // see the loadFailed field - writing now would erase real flag history
        if (!flushQueued.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            flushQueued.set(false);
            try {
                Map<String, Map<String, Integer>> snapshot = new java.util.HashMap<>();
                flags.forEach((uuid, counters) -> snapshot.put(uuid.toString(), new java.util.HashMap<>(counters)));
                JsonFiles.writeAtomic(flagsFile, GSON.toJson(snapshot));
            } catch (IOException ex) {
                errorReporter.accept(ex);
            }
        });
    }
}
