package com.mellishy.customtag.audit;

import com.google.gson.Gson;
import com.mellishy.customtag.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The central audit trail: every important action anywhere in the plugin -
 * approvals, rejections, refunds, AI decisions, token movements, security blocks, staff
 * overrides - is appended here as one JSON line per entry, one file per day under
 * {@code plugins/CustomTag/logs/audit/}. JSONL keeps the files export-friendly (each line is a
 * complete JSON document), append-only safe, and greppable by admins without any tooling.
 *
 * Writes are queued to the injected IO executor - never the main thread (spec: "Main Thread
 * Logging" is prohibited). A bounded in-memory window backs the instant staff search command;
 * the files hold the full history subject to retention.
 *
 * BUKKIT-FREE and unit-testable.
 */
public class AuditLogService {

    private static final Gson GSON = new Gson();

    private final Path folder;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final LongSupplier clock;

    private final Deque<AuditEntry> recent = new ArrayDeque<>();
    private static final int RECENT_CAP = 2000;

    private volatile int retentionDays = 90;

    public AuditLogService(Path folder, Executor ioExecutor, Consumer<Exception> errorReporter,
                           LongSupplier clock) {
        this.folder = folder;
        this.ioExecutor = ioExecutor;
        this.errorReporter = errorReporter;
        this.clock = clock;
    }

    /** {@code <= 0} keeps audit files forever. */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /** Appends one entry (async, ordered by the single-threaded IO executor). */
    public void log(AuditCategory category, String action, String actorName, String targetName,
                    String targetCustomId, String requestId, String detail) {
        AuditEntry entry = new AuditEntry(clock.getAsLong(), category, action, actorName,
                targetName, targetCustomId, requestId, detail);
        synchronized (recent) {
            recent.addLast(entry);
            while (recent.size() > RECENT_CAP) recent.removeFirst();
        }
        ioExecutor.execute(() -> {
            try {
                JsonFiles.appendLine(fileFor(entry.at()), GSON.toJson(entry));
            } catch (IOException ex) {
                errorReporter.accept(ex);
            }
        });
    }

    private Path fileFor(long epochMillis) {
        LocalDate day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        return folder.resolve("audit-" + day + ".jsonl");
    }

    /**
     * In-memory search over the recent window, newest first. {@code filter} matches (case-
     * insensitive substring) against actor, target, custom id, request id, action and detail;
     * null/blank filter returns everything. Instant - never touches disk, safe on the main thread.
     */
    public List<AuditEntry> searchRecent(String filter, AuditCategory category, int limit) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        List<AuditEntry> out = new ArrayList<>();
        synchronized (recent) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                AuditEntry e = it.next();
                if (category != null && e.category() != category) continue;
                if (!needle.isEmpty() && !matches(e, needle)) continue;
                out.add(e);
            }
        }
        return out;
    }

    private boolean matches(AuditEntry e, String needle) {
        return containsIgnoreCase(e.actorName(), needle)
                || containsIgnoreCase(e.targetName(), needle)
                || containsIgnoreCase(e.targetCustomId(), needle)
                || containsIgnoreCase(e.requestId(), needle)
                || containsIgnoreCase(e.action(), needle)
                || containsIgnoreCase(e.detail(), needle);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Deletes audit files older than the retention window. Runs on the IO executor - schedule
     * from the plugin once at startup and periodically (e.g. daily).
     */
    public void runRetentionSweep() {
        int days = retentionDays;
        if (days <= 0) return;
        ioExecutor.execute(() -> {
            LocalDate cutoff = Instant.ofEpochMilli(clock.getAsLong())
                    .atZone(ZoneId.systemDefault()).toLocalDate().minusDays(days);
            try (Stream<Path> files = Files.exists(folder) ? Files.list(folder) : Stream.empty()) {
                files.filter(f -> f.getFileName().toString().startsWith("audit-")
                                && f.getFileName().toString().endsWith(".jsonl"))
                        .forEach(f -> {
                            String name = f.getFileName().toString();
                            String dateText = name.substring("audit-".length(), name.length() - ".jsonl".length());
                            try {
                                if (LocalDate.parse(dateText).isBefore(cutoff)) {
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
}
