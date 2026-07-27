package com.mellishy.customtag.request;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mellishy.customtag.util.JsonFiles;
import com.mellishy.customtag.util.PersistentCounters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The central request/queue authority: every tag submission becomes a
 * {@link TagRequest} with a unique, never-reused id ({@code REQ-00000001}), a strict status
 * lifecycle, full transition history, staff locking, capacity limits, priority-aware FIFO
 * ordering and automatic expiration. No system (GUI, AI, webhook, command, API) may change a
 * request's state except through the methods here - that is what makes undo/reopen, audit
 * trails and duplicate protection possible.
 *
 * Persistence is a single JSON store (open requests + a capped history of closed ones) written
 * atomically off-thread, so pending requests survive restarts and crashes (queue recovery).
 *
 * DELIBERATELY BUKKIT-FREE: everything here is plain Java (clock + executor are injected), so
 * the whole queue lifecycle is unit-testable without a server, matching the existing test
 * philosophy of this project.
 *
 * THREAD SAFETY: all mutating methods are synchronized (request volume is human-scale - a few
 * per second at the absolute worst - so one coarse lock is both correct and effectively free);
 * every read hands out {@link TagRequest#copy() copies}, never live instances.
 */
public class RequestManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Gson envelope for the on-disk store. */
    private record StoreDto(List<TagRequest> open, List<TagRequest> closed) {}

    private final Path storeFile;
    private final PersistentCounters counters;
    private final Executor ioExecutor;
    private final Consumer<Exception> errorReporter;
    private final LongSupplier clock;

    /** Open requests by request id, in insertion order (drives stable FIFO views). */
    private final Map<String, TagRequest> open = new LinkedHashMap<>();
    /** Recently closed requests kept for history/search/undo, oldest first, capped. */
    private final Deque<TagRequest> closed = new ArrayDeque<>();

    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    /**
     * Set when {@link #load()} could not read an existing store. The queue is then empty only
     * because we couldn't see it - saving over the file would throw away every pending request
     * that is still sitting on disk, and players would have spent tokens on requests that no
     * longer exist anywhere.
     */
    private volatile boolean loadFailed;

    // ---- tunables (set from queue/settings.yml on startup + reload) ----
    private volatile int globalPendingLimit = 50;
    private volatile long lockTimeoutMillis = 5 * 60_000L;
    private volatile int closedHistoryCap = 500;

    public RequestManager(Path storeFile, PersistentCounters counters, Executor ioExecutor,
                          Consumer<Exception> errorReporter, LongSupplier clock) {
        this.storeFile = storeFile;
        this.counters = counters;
        this.ioExecutor = ioExecutor;
        this.errorReporter = errorReporter;
        this.clock = clock;
        load();
    }

    // ---- configuration ----

    /** {@code <= 0} means unlimited. */
    public void setGlobalPendingLimit(int limit) { this.globalPendingLimit = limit; }
    public void setLockTimeoutMillis(long millis) { this.lockTimeoutMillis = Math.max(10_000L, millis); }
    /** Applied immediately, not just to future closures - lowering it on /ct reload has to actually free the memory. */
    public synchronized void setClosedHistoryCap(int cap) {
        this.closedHistoryCap = Math.max(50, cap);
        if (closed.size() > closedHistoryCap) {
            trimClosedHistory();
            queueSave();
        }
    }
    public int globalPendingLimit() { return globalPendingLimit; }

    // ---- creation ----

    /** True when the network-wide pending queue has no room for another request. */
    public synchronized boolean isQueueFull() {
        return globalPendingLimit > 0 && open.size() >= globalPendingLimit;
    }

    /** How many open (undecided) requests this player currently has in the queue. */
    public synchronized long openCountOf(UUID player) {
        return open.values().stream().filter(r -> r.getPlayerUuid().equals(player)).count();
    }

    /**
     * Registers a new request in the global queue. Returns the created request copy, or empty when
     * the queue is full - the capacity check and the insert happen under one lock so two
     * simultaneous submissions can never both squeeze into the last slot.
     */
    public synchronized Optional<TagRequest> create(UUID playerUuid, String playerName, String playerCustomId,
                                                    String tagId, String rawText, String plainText,
                                                    String serverName, int priority) {
        if (isQueueFull()) return Optional.empty();
        String requestId = String.format("REQ-%08d", counters.next("request"));
        TagRequest request = new TagRequest(requestId, playerUuid, playerName, playerCustomId,
                tagId, rawText, plainText, serverName, priority, clock.getAsLong());
        open.put(requestId, request);
        queueSave();
        return Optional.of(request.copy());
    }

    // ---- reads (always copies) ----

    public synchronized Optional<TagRequest> byId(String requestId) {
        TagRequest r = open.get(requestId);
        if (r != null) return Optional.of(r.copy());
        return closed.stream().filter(c -> c.getRequestId().equals(requestId)).findFirst().map(TagRequest::copy);
    }

    /** The open request tracking a specific TagEntry, if any. */
    public synchronized Optional<TagRequest> byTag(UUID playerUuid, String tagId) {
        return open.values().stream()
                .filter(r -> r.getPlayerUuid().equals(playerUuid) && r.getTagId().equals(tagId))
                .findFirst().map(TagRequest::copy);
    }

    /**
     * The open queue in review order: higher-priority first (priority 1 beats priority 3), then
     * strictly oldest-first inside the same priority - staff must never see requests in a random
     * order.
     */
    public synchronized List<TagRequest> openRequests() {
        return open.values().stream()
                .sorted(Comparator.comparingInt(TagRequest::getPriority)
                        .thenComparingLong(TagRequest::getCreatedAt))
                .map(TagRequest::copy)
                .toList();
    }

    /** Requests escalated to staff by the AI (the "AI Review Queue"). */
    public synchronized List<TagRequest> aiReviewQueue() {
        return open.values().stream()
                .filter(r -> r.getStatus() == RequestStatus.AI_REVIEW)
                .sorted(Comparator.comparingLong(TagRequest::getCreatedAt))
                .map(TagRequest::copy)
                .toList();
    }

    /** Most recent closed requests, newest first, for history commands/menus. */
    public synchronized List<TagRequest> recentClosed(int limit) {
        List<TagRequest> out = new ArrayList<>();
        var it = closed.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next().copy());
        }
        return out;
    }

    /** Every request (open first, then recent closed) belonging to one player, newest first. */
    public synchronized List<TagRequest> historyOf(UUID player, int limit) {
        if (limit <= 0) return List.of();
        List<TagRequest> out = new ArrayList<>();
        open.values().stream()
                .filter(r -> r.getPlayerUuid().equals(player))
                .sorted(Comparator.comparingLong(TagRequest::getCreatedAt).reversed())
                .limit(limit)
                .forEach(r -> out.add(r.copy()));
        var it = closed.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            TagRequest r = it.next();
            if (r.getPlayerUuid().equals(player)) out.add(r.copy());
        }
        // List.copyOf, not subList: subList returns a VIEW backed by `out`, so the caller received
        // a list holding a strong reference to the full, untrimmed result - and one that throws
        // ConcurrentModificationException if anything ever touches the backing list. Every other
        // read on this class hands back an independent snapshot; this one now does too.
        return List.copyOf(out);
    }

    public synchronized Map<RequestStatus, Long> statusCounts() {
        Map<RequestStatus, Long> counts = new EnumMap<>(RequestStatus.class);
        open.values().forEach(r -> counts.merge(r.getStatus(), 1L, Long::sum));
        closed.forEach(r -> counts.merge(r.getStatus(), 1L, Long::sum));
        return counts;
    }

    public synchronized int openCount() {
        return open.size();
    }

    public long totalCreated() {
        return counters.current("request");
    }

    // ---- locking (no two staff may ever act on the same request at once) ----

    /**
     * Attempts to take the exclusive review lock. Returns true when this staff member now holds
     * it (including when they already did). A lock held by someone else expires automatically
     * after the configured timeout, so a staff member who logs off mid-review can never jam a
     * request forever.
     */
    public synchronized boolean tryLock(String requestId, UUID staff, String staffName) {
        TagRequest r = open.get(requestId);
        if (r == null) return false;
        long now = clock.getAsLong();
        if (r.getLockedBy() != null && !r.getLockedBy().equals(staff)
                && now - r.getLockedAt() < lockTimeoutMillis) {
            return false;
        }
        r.lock(staff, staffName, now);
        queueSave();
        return true;
    }

    public synchronized void unlock(String requestId, UUID staff) {
        TagRequest r = open.get(requestId);
        if (r != null && staff.equals(r.getLockedBy())) {
            r.unlock();
            queueSave();
        }
    }

    /** True when someone ELSE currently holds a live (non-expired) lock on this request. */
    public synchronized boolean isLockedByOther(String requestId, UUID staff) {
        TagRequest r = open.get(requestId);
        if (r == null || r.getLockedBy() == null || r.getLockedBy().equals(staff)) return false;
        return clock.getAsLong() - r.getLockedAt() < lockTimeoutMillis;
    }

    // ---- transitions ----

    /**
     * Marks the final decision on an open request and moves it to the closed history.
     * Returns the updated copy, or empty when the request wasn't open (already decided
     * elsewhere - the duplicate-approval guard).
     */
    public synchronized Optional<TagRequest> decide(String requestId, RequestStatus decision,
                                                    DecisionActor actor, String actorName,
                                                    String reason, boolean refunded) {
        TagRequest r = open.get(requestId);
        if (r == null || !r.getStatus().isOpen()) return Optional.empty();
        if (reason != null) r.setRejectReason(reason);
        r.setRefunded(refunded);
        r.unlock();
        r.transition(decision, actor, actorName, reason, clock.getAsLong());
        close(requestId, r);
        return Optional.of(r.copy());
    }

    /** Marks a request as being actively processed (AI call in flight / staff opened it). */
    public synchronized Optional<TagRequest> markProcessing(String requestId, DecisionActor actor, String actorName) {
        TagRequest r = open.get(requestId);
        if (r == null || !r.getStatus().isOpen()) return Optional.empty();
        r.transition(RequestStatus.PROCESSING, actor, actorName, null, clock.getAsLong());
        queueSave();
        return Optional.of(r.copy());
    }

    /** Escalates to the staff review queue with the AI's metadata attached. */
    public synchronized Optional<TagRequest> escalateToStaff(String requestId, String provider, String model,
                                                             int confidence, String aiReason) {
        TagRequest r = open.get(requestId);
        if (r == null || !r.getStatus().isOpen()) return Optional.empty();
        r.setAiResult(provider, model, confidence, aiReason);
        r.transition(RequestStatus.AI_REVIEW, DecisionActor.AI, provider, aiReason, clock.getAsLong());
        queueSave();
        return Optional.of(r.copy());
    }

    /** Records AI metadata on a request without changing its status (used right before an AI decision). */
    public synchronized void attachAiResult(String requestId, String provider, String model,
                                            int confidence, String aiReason) {
        TagRequest r = open.get(requestId);
        if (r != null) {
            r.setAiResult(provider, model, confidence, aiReason);
            queueSave();
        }
    }

    /** Player withdrew their own pending request. */
    public synchronized Optional<TagRequest> cancel(String requestId, String playerName) {
        TagRequest r = open.get(requestId);
        if (r == null || !r.getStatus().isOpen()) return Optional.empty();
        r.transition(RequestStatus.CANCELLED, DecisionActor.PLAYER, playerName, null, clock.getAsLong());
        close(requestId, r);
        return Optional.of(r.copy());
    }

    /** Staff silently removed the request (no player notification - mirrors TagService#rejectSilent). */
    public synchronized Optional<TagRequest> remove(String requestId, DecisionActor actor, String actorName, boolean refunded) {
        TagRequest r = open.get(requestId);
        if (r == null || !r.getStatus().isOpen()) return Optional.empty();
        r.setRefunded(refunded);
        r.transition(RequestStatus.REMOVED, actor, actorName, null, clock.getAsLong());
        close(requestId, r);
        return Optional.of(r.copy());
    }

    /**
     * Reopens a CLOSED request so it can be reviewed again - the undo path; no decision should
     * ever be irreversible. The request re-enters the open queue with
     * status {@link RequestStatus#REOPENED}, keeping its original id and full history.
     * Returns empty when the id is unknown, still open, or the queue is full.
     */
    public synchronized Optional<TagRequest> reopen(String requestId, DecisionActor actor, String actorName) {
        if (open.containsKey(requestId)) return Optional.empty();
        TagRequest target = null;
        for (TagRequest r : closed) {
            if (r.getRequestId().equals(requestId)) {
                target = r;
                break;
            }
        }
        if (target == null) return Optional.empty();
        if (isQueueFull()) return Optional.empty();
        closed.remove(target);
        target.setRefunded(false);
        target.transition(RequestStatus.REOPENED, actor, actorName, "reopened", clock.getAsLong());
        open.put(requestId, target);
        queueSave();
        return Optional.of(target.copy());
    }

    /**
     * Expires every open request older than {@code maxAgeMillis} and returns their copies so the
     * caller can refund tokens / notify players / fire webhooks. {@code <= 0} disables expiry.
     */
    public synchronized List<TagRequest> expireSweep(long maxAgeMillis) {
        if (maxAgeMillis <= 0) return List.of();
        long cutoff = clock.getAsLong() - maxAgeMillis;
        List<TagRequest> expired = new ArrayList<>();
        for (TagRequest r : new ArrayList<>(open.values())) {
            if (r.getCreatedAt() < cutoff && r.getStatus().isOpen()) {
                r.transition(RequestStatus.EXPIRED, DecisionActor.SYSTEM, "expiry", null, clock.getAsLong());
                close(r.getRequestId(), r);
                expired.add(r.copy());
            }
        }
        return expired;
    }

    private void close(String requestId, TagRequest request) {
        open.remove(requestId);
        closed.addLast(request);
        trimClosedHistory();
        queueSave();
    }

    // ---- persistence ----

    private void load() {
        String json;
        try {
            json = JsonFiles.read(storeFile);
        } catch (IOException ex) {
            loadFailed = true;
            errorReporter.accept(new IOException("Could not read " + storeFile.getFileName() + " - the request "
                    + "queue is EMPTY for this session and will NOT be saved over, so nothing on disk is lost. "
                    + "Fix the file/permissions and restart to get the pending queue back.", ex));
            return;
        }
        if (json == null || json.isBlank()) return;
        try {
            StoreDto dto = GSON.fromJson(json, StoreDto.class);
            if (dto == null) return;
            if (dto.open() != null) {
                for (TagRequest r : dto.open()) {
                    if (r != null && r.getRequestId() != null) open.put(r.getRequestId(), r);
                }
            }
            if (dto.closed() != null) {
                dto.closed().stream().filter(r -> r != null && r.getRequestId() != null).forEach(closed::addLast);
            }
            // The cap is otherwise only ever applied by close(), so a store written under a larger
            // cap (or with the cap since lowered in config) kept every one of those entries resident
            // for the whole session - on a quiet server no new close() ever runs to trim them down.
            trimClosedHistory();
        } catch (Exception ex) {
            // corrupt store must never take the queue down - recover with what we have (the tags
            // themselves are safe in player storage; only queue metadata would be rebuilt)
            errorReporter.accept(ex);
        }
    }

    private void trimClosedHistory() {
        while (closed.size() > closedHistoryCap) {
            closed.removeFirst();
        }
    }

    private void queueSave() {
        if (saveQueued.compareAndSet(false, true)) {
            ioExecutor.execute(() -> {
                saveQueued.set(false);
                flushNow();
            });
        }
    }

    /** Synchronous store write - also invoked once on plugin shutdown. */
    public void flushNow() {
        if (loadFailed) return; // see the loadFailed field - writing now would wipe the pending queue
        StoreDto dto;
        synchronized (this) {
            dto = new StoreDto(
                    open.values().stream().map(TagRequest::copy).toList(),
                    closed.stream().map(TagRequest::copy).toList());
        }
        try {
            JsonFiles.writeAtomic(storeFile, GSON.toJson(dto));
        } catch (IOException ex) {
            errorReporter.accept(ex);
        }
    }
}
