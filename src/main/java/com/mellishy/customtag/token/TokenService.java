package com.mellishy.customtag.token;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.util.JsonFiles;
import com.mellishy.customtag.util.PersistentCounters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The single token authority - there are never multiple writers to a balance: every
 * balance change in the whole plugin funnels through {@link #apply}, which validates, mutates
 * the {@link PlayerData} balance, and appends an immutable {@link TokenTransaction} row with a
 * durable {@code TOKEN-XXXXXXXX} id to the on-disk ledger. Nothing else in the codebase calls
 * {@code PlayerData.addTokens} for economy operations anymore.
 *
 * Also owns the freeze system: a frozen account (suspected duping/abuse) can not spend or
 * receive tokens until unfrozen; the freeze set is persisted so it survives restarts.
 *
 * THREAD SAFETY / BUKKIT-FREE: balance mutation happens on whatever thread owns the live
 * {@link PlayerData} (the main thread, same contract as the rest of the plugin); ledger writes
 * are queued to the injected IO executor. No Bukkit types - the whole economy is unit-testable.
 */
public class TokenService {

    private static final Gson GSON = new Gson();

    /** Everything the caller needs to know about an attempted balance change. */
    public sealed interface Result {
        record Success(TokenTransaction transaction) implements Result {}
        record Frozen() implements Result {}
        record InsufficientBalance(int balance) implements Result {}
        record InvalidAmount() implements Result {}
        /** The credit would push the balance past {@link Integer#MAX_VALUE} - refused, balance untouched. */
        record BalanceOverflow(int balance) implements Result {}
    }

    private final Path ledgerFolder;
    private final Path freezeFile;
    private final PersistentCounters counters;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final LongSupplier clock;

    /** Frozen accounts: uuid -> human-readable reason. */
    private final Map<UUID, String> frozen = new ConcurrentHashMap<>();
    /** Recent transactions kept in memory for instant staff queries (ledger files hold the full history). */
    private final Deque<TokenTransaction> recent = new ArrayDeque<>();
    private static final int RECENT_CAP = 1000;

    /**
     * Set when {@link #loadFreezes()} could not fully read the freeze file. The in-memory set is
     * then missing suspensions that really are on disk, so writing it back would silently lift them
     * for good.
     */
    private volatile boolean loadFailed;

    /** Days to keep monthly ledger files. {@code <= 0} keeps forever. Same knob as audit retention. */
    private volatile int retentionDays = 90;

    public TokenService(Path ledgerFolder, Path freezeFile, PersistentCounters counters,
                        Executor ioExecutor, Consumer<Exception> errorReporter, LongSupplier clock) {
        this.ledgerFolder = ledgerFolder;
        this.freezeFile = freezeFile;
        this.counters = counters;
        this.ioExecutor = ioExecutor;
        this.errorReporter = errorReporter;
        this.clock = clock;
        loadFreezes();
    }

    /**
     * Applies one balance change. {@code amount} is the POSITIVE magnitude; the sign comes from
     * the transaction type. The check-and-mutate happens on the calling (main) thread against the
     * live PlayerData, so there is no read-modify-write race - and the ledger row is written
     * before this method returns control flow-wise (queued, ordered, off-thread).
     *
     * @param actorName who initiated this (player name, staff name, "AI", store name, ...)
     * @param reason    free-form context, e.g. a request id or command
     */
    public Result apply(PlayerData data, String playerCustomId, TokenTransactionType type,
                        int amount, String reason, String actorName) {
        if (amount <= 0) return new Result.InvalidAmount();
        if (frozen.containsKey(data.getUuid())) return new Result.Frozen();

        // Widened to long before the arithmetic. `amount * type.sign()` and the balance check that
        // follows are both plain int math, so a large credit used to wrap around into a NEGATIVE
        // balance, which PlayerData#addTokens then floored to zero via Math.max(0, ...) - an admin
        // granting tokens to an already-rich account could silently WIPE it, and the ledger row was
        // written as if it had succeeded. Refused outright now; the balance is left untouched.
        long delta = (long) amount * type.sign();
        long projected = (long) data.getTokens() + delta;
        if (projected < 0) {
            return new Result.InsufficientBalance(data.getTokens());
        }
        if (projected > Integer.MAX_VALUE) {
            return new Result.BalanceOverflow(data.getTokens());
        }
        data.addTokens((int) delta);

        TokenTransaction tx = new TokenTransaction(
                String.format("TOKEN-%08d", counters.next("token")),
                data.getUuid(), data.getLastKnownName(), playerCustomId,
                type, (int) delta, data.getTokens(), reason, actorName, clock.getAsLong());
        record(tx);
        return new Result.Success(tx);
    }

    private void record(TokenTransaction tx) {
        synchronized (recent) {
            recent.addLast(tx);
            while (recent.size() > RECENT_CAP) recent.removeFirst();
        }
        // A transaction accepted during shutdown still reaches disk: the executor injected here
        // runs a rejected task inline rather than dropping it (see PlatformServices#guardedIo).
        ioExecutor.execute(() -> appendToLedger(tx));
    }

    private void appendToLedger(TokenTransaction tx) {
        try {
            LocalDate day = java.time.Instant.ofEpochMilli(tx.at()).atZone(ZoneId.systemDefault()).toLocalDate();
            Path file = ledgerFolder.resolve("tokens-" + day.getYear()
                    + "-" + String.format("%02d", day.getMonthValue()) + ".jsonl");
            JsonFiles.appendLine(file, GSON.toJson(tx));
        } catch (IOException ex) {
            errorReporter.accept(ex);
        }
    }

    /** Most recent transactions of one player, newest first (in-memory window). */
    public List<TokenTransaction> recentOf(UUID player, int limit) {
        List<TokenTransaction> out = new ArrayList<>();
        synchronized (recent) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                TokenTransaction tx = it.next();
                if (tx.playerUuid().equals(player)) out.add(tx);
            }
        }
        return out;
    }

    /** Most recent transactions across the whole server, newest first (in-memory window). */
    public List<TokenTransaction> recentAll(int limit) {
        List<TokenTransaction> out = new ArrayList<>();
        synchronized (recent) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                out.add(it.next());
            }
        }
        return out;
    }

    public long totalTransactions() {
        return counters.current("token");
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * Deletes monthly ledger files older than the retention window. Ledger files are named
     * {@code tokens-YYYY-MM.jsonl} - a month whose last day is before the cutoff is removed.
     * Same schedule as {@link com.mellishy.customtag.audit.AuditLogService#runRetentionSweep}.
     */
    public void runRetentionSweep() {
        int days = retentionDays;
        if (days <= 0) return;
        ioExecutor.execute(() -> {
            LocalDate cutoff = Instant.ofEpochMilli(clock.getAsLong())
                    .atZone(ZoneId.systemDefault()).toLocalDate().minusDays(days);
            YearMonth cutoffMonth = YearMonth.from(cutoff);
            try (Stream<Path> files = Files.exists(ledgerFolder) ? Files.list(ledgerFolder) : Stream.empty()) {
                files.filter(f -> {
                            String name = f.getFileName().toString();
                            return name.startsWith("tokens-") && name.endsWith(".jsonl");
                        })
                        .forEach(f -> {
                            String name = f.getFileName().toString();
                            String stamp = name.substring("tokens-".length(), name.length() - ".jsonl".length());
                            try {
                                if (YearMonth.parse(stamp).isBefore(cutoffMonth)) {
                                    Files.deleteIfExists(f);
                                }
                            } catch (Exception ignored) {
                                // unparseable file name - leave it alone rather than guess
                            }
                        });
            } catch (IOException ex) {
                errorReporter.accept(ex);
            }
        });
    }

    // ---- freeze system ----

    public boolean isFrozen(UUID player) {
        return frozen.containsKey(player);
    }

    public Optional<String> freezeReason(UUID player) {
        return Optional.ofNullable(frozen.get(player));
    }

    public void freeze(UUID player, String reason) {
        frozen.put(player, reason == null ? "frozen" : reason);
        saveFreezes();
    }

    /** @return true when the player was actually frozen before this call. */
    public boolean unfreeze(UUID player) {
        boolean removed = frozen.remove(player) != null;
        if (removed) saveFreezes();
        return removed;
    }

    private void loadFreezes() {
        String json;
        try {
            json = JsonFiles.read(freezeFile);
        } catch (IOException ex) {
            loadFailed = true;
            errorReporter.accept(new IOException("Could not read " + freezeFile.getFileName()
                    + " - EVERY FROZEN ACCOUNT IS UNFROZEN for this session. The file will not be overwritten, "
                    + "so restarting after fixing the permissions restores them. Re-freeze manually if needed.", ex));
            return;
        }
        if (json == null || json.isBlank()) return;
        try {
            Map<String, String> raw = GSON.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (raw == null) return;
            // Per-entry, not one try/catch around the whole loop: a single unparseable uuid used to
            // abort the load partway through, leaving the accounts after it in the file unfrozen -
            // and the next freeze()/unfreeze() would then write that truncated map back out, making
            // the loss permanent. A suspected duper quietly regaining spending rights is exactly the
            // failure this system exists to prevent.
            raw.forEach((uuid, reason) -> {
                try {
                    frozen.put(UUID.fromString(uuid), reason == null ? "frozen" : reason);
                } catch (IllegalArgumentException ex) {
                    loadFailed = true;
                    errorReporter.accept(new IllegalArgumentException("Unreadable frozen-account entry '"
                            + uuid + "' in " + freezeFile.getFileName() + " - that account is NOT frozen.", ex));
                }
            });
        } catch (Exception ex) {
            loadFailed = true;
            errorReporter.accept(ex);
        }
    }

    private void saveFreezes() {
        if (loadFailed) return; // see the loadFailed field - writing now would drop real freezes
        // snapshot on the caller's thread so the async write can never serialize a map that is
        // being mutated by a concurrent freeze()/unfreeze()
        Map<String, String> snapshot = new java.util.HashMap<>();
        frozen.forEach((uuid, reason) -> snapshot.put(uuid.toString(), reason));
        ioExecutor.execute(() -> writeFreezes(snapshot));
    }

    private void writeFreezes(Map<String, String> snapshot) {
        try {
            JsonFiles.writeAtomic(freezeFile, GSON.toJson(snapshot));
        } catch (IOException ex) {
            errorReporter.accept(ex);
        }
    }

    /**
     * Synchronous freeze write, called once on plugin shutdown. {@link #saveFreezes()} is
     * fire-and-forget on the IO executor, so a freeze applied moments before the server stops could
     * previously be dropped when that executor was shut down before the task ran - the account came
     * back unfrozen, with staff believing the suspension had stuck.
     */
    public void flushNow() {
        if (loadFailed) return;
        Map<String, String> snapshot = new java.util.HashMap<>();
        frozen.forEach((uuid, reason) -> snapshot.put(uuid.toString(), reason));
        writeFreezes(snapshot);
    }
}
