package com.mellishy.customtag.request;

import com.mellishy.customtag.util.PersistentCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The request queue is the heart of the whole moderation platform: unique never-reused ids,
 * capacity limits, staff locking, duplicate-decision guards, undo/reopen and expiry all live
 * here, fully Bukkit-free. A regression in any of these silently corrupts moderation state,
 * so the full lifecycle is pinned down explicitly.
 */
class RequestManagerTest {

    @TempDir
    Path tempDir;

    /** Controllable clock so lock timeouts and expiry can be tested deterministically. */
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private RequestManager manager;
    private PersistentCounters counters;

    @BeforeEach
    void setUp() {
        counters = new PersistentCounters(tempDir.resolve("counters.json"), Runnable::run, ex -> fail(ex));
        manager = new RequestManager(tempDir.resolve("requests.json"), counters, Runnable::run,
                ex -> fail(ex), now::get);
    }

    private TagRequest create(UUID player, String text, int priority) {
        return manager.create(player, "Tester", "<#0001-1>", UUID.randomUUID().toString(),
                text, text, "test-server", priority).orElseThrow();
    }

    @Test
    void create_assignsSequentialNeverReusedRequestIds() {
        TagRequest first = create(UUID.randomUUID(), "one", 3);
        TagRequest second = create(UUID.randomUUID(), "two", 3);

        assertEquals("REQ-00000001", first.getRequestId());
        assertEquals("REQ-00000002", second.getRequestId());
        assertEquals(RequestStatus.PENDING, first.getStatus());
    }

    @Test
    void create_refusesWhenGlobalPendingLimitReached() {
        manager.setGlobalPendingLimit(2);
        create(UUID.randomUUID(), "one", 3);
        create(UUID.randomUUID(), "two", 3);

        assertTrue(manager.isQueueFull());
        assertTrue(manager.create(UUID.randomUUID(), "Tester", "<#X>", "tag", "three", "three",
                "test-server", 3).isEmpty());
    }

    @Test
    void openRequests_sortsByPriorityThenOldestFirst() {
        TagRequest low = create(UUID.randomUUID(), "low", 3);
        now.addAndGet(1000);
        TagRequest high = create(UUID.randomUUID(), "high", 1);
        now.addAndGet(1000);
        TagRequest lowLater = create(UUID.randomUUID(), "low-later", 3);

        List<TagRequest> order = manager.openRequests();
        assertEquals(high.getRequestId(), order.get(0).getRequestId());
        assertEquals(low.getRequestId(), order.get(1).getRequestId());
        assertEquals(lowLater.getRequestId(), order.get(2).getRequestId());
    }

    @Test
    void decide_movesRequestToClosedHistory_andBlocksDoubleDecisions() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);

        Optional<TagRequest> approved = manager.decide(request.getRequestId(),
                RequestStatus.APPROVED, DecisionActor.STAFF, "Admin", null, false);
        assertTrue(approved.isPresent());
        assertEquals(RequestStatus.APPROVED, approved.get().getStatus());
        assertEquals("Admin", approved.get().getDecidedByName());

        // the duplicate-decision guard: a second decision on the same request must be refused
        assertTrue(manager.decide(request.getRequestId(), RequestStatus.REJECTED,
                DecisionActor.STAFF, "OtherAdmin", "late", false).isEmpty());
        assertEquals(0, manager.openCount());
        // still findable in history by id
        assertTrue(manager.byId(request.getRequestId()).isPresent());
    }

    @Test
    void locking_blocksOtherStaff_untilTimeoutExpires() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);
        UUID staffA = UUID.randomUUID();
        UUID staffB = UUID.randomUUID();
        manager.setLockTimeoutMillis(60_000);

        assertTrue(manager.tryLock(request.getRequestId(), staffA, "StaffA"));
        assertFalse(manager.tryLock(request.getRequestId(), staffB, "StaffB"));
        assertTrue(manager.isLockedByOther(request.getRequestId(), staffB));
        // re-acquiring your own lock is fine
        assertTrue(manager.tryLock(request.getRequestId(), staffA, "StaffA"));

        // after the timeout the lock expires automatically (staff logged off mid-review)
        now.addAndGet(61_000);
        assertTrue(manager.tryLock(request.getRequestId(), staffB, "StaffB"));
    }

    @Test
    void reopen_putsClosedRequestBackInTheQueue_withHistoryPreserved() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);
        manager.decide(request.getRequestId(), RequestStatus.REJECTED, DecisionActor.STAFF, "Admin", "nope", true);

        Optional<TagRequest> reopened = manager.reopen(request.getRequestId(), DecisionActor.STAFF, "Admin");

        assertTrue(reopened.isPresent());
        assertEquals(RequestStatus.REOPENED, reopened.get().getStatus());
        assertTrue(reopened.get().getStatus().isOpen());
        assertFalse(reopened.get().isRefunded(), "reopen must clear the refunded flag");
        assertEquals(1, manager.openCount());
        // full transition history survives the round trip
        assertTrue(reopened.get().getHistory().size() >= 2);
        // reopening something that is already open must fail
        assertTrue(manager.reopen(request.getRequestId(), DecisionActor.STAFF, "Admin").isEmpty());
    }

    @Test
    void expireSweep_closesOnlyRequestsOlderThanTheCutoff() {
        TagRequest old = create(UUID.randomUUID(), "old", 3);
        now.addAndGet(10_000);
        TagRequest fresh = create(UUID.randomUUID(), "fresh", 3);

        now.addAndGet(5_000);
        List<TagRequest> expired = manager.expireSweep(12_000);

        assertEquals(1, expired.size());
        assertEquals(old.getRequestId(), expired.get(0).getRequestId());
        assertEquals(RequestStatus.EXPIRED, expired.get(0).getStatus());
        assertEquals(1, manager.openCount());
        assertTrue(manager.byId(fresh.getRequestId()).orElseThrow().getStatus().isOpen());
    }

    @Test
    void store_survivesRestart_openAndClosedRequestsAndIdCounter() {
        TagRequest open = create(UUID.randomUUID(), "still-open", 2);
        TagRequest closed = create(UUID.randomUUID(), "decided", 3);
        manager.decide(closed.getRequestId(), RequestStatus.APPROVED, DecisionActor.AI, "openai", null, false);
        manager.flushNow();
        counters.flushNow();

        // simulate a full restart: brand-new instances reading the same files
        PersistentCounters counters2 = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        RequestManager restarted = new RequestManager(tempDir.resolve("requests.json"), counters2,
                Runnable::run, ex -> fail(ex), now::get);

        assertEquals(1, restarted.openCount());
        assertEquals(open.getRequestId(), restarted.openRequests().get(0).getRequestId());
        assertEquals(RequestStatus.APPROVED, restarted.byId(closed.getRequestId()).orElseThrow().getStatus());
        // id counter continues - never reuses an id from before the restart
        TagRequest next = restarted.create(UUID.randomUUID(), "T", "<#Y>", "tag", "x", "x", "s", 3).orElseThrow();
        assertEquals("REQ-00000003", next.getRequestId());
    }

    @Test
    void unlock_releasesLockForOtherStaff() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);
        UUID staffA = UUID.randomUUID();
        UUID staffB = UUID.randomUUID();
        assertTrue(manager.tryLock(request.getRequestId(), staffA, "StaffA"));

        manager.unlock(request.getRequestId(), staffA);

        assertFalse(manager.isLockedByOther(request.getRequestId(), staffB));
        assertTrue(manager.tryLock(request.getRequestId(), staffB, "StaffB"));
    }

    @Test
    void unlockByWrongStaff_doesNotReleaseSomeoneElsesLock() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);
        UUID staffA = UUID.randomUUID();
        UUID staffB = UUID.randomUUID();
        assertTrue(manager.tryLock(request.getRequestId(), staffA, "StaffA"));

        manager.unlock(request.getRequestId(), staffB);

        assertTrue(manager.isLockedByOther(request.getRequestId(), staffB),
                "only the lock holder (or expiry) may clear a review lock");
    }

    @Test
    void decide_clearsLockSoHistoryLookupsAreNotBlocked() {
        TagRequest request = create(UUID.randomUUID(), "tag", 3);
        UUID staff = UUID.randomUUID();
        assertTrue(manager.tryLock(request.getRequestId(), staff, "Staff"));

        manager.decide(request.getRequestId(), RequestStatus.APPROVED, DecisionActor.STAFF, "Staff", null, false);

        assertFalse(manager.isLockedByOther(request.getRequestId(), UUID.randomUUID()));
    }

    @Test
    void setClosedHistoryCap_trimsImmediately_notOnlyOnNextClose() {
        // floor is 50 (see setClosedHistoryCap) - fill past it, then lower... wait, floor IS 50,
        // so the only way to observe a trim-on-set is to load more than the new cap. Fill to 80
        // under a raised cap first, then drop back to the floor.
        manager.setClosedHistoryCap(80);
        for (int i = 0; i < 80; i++) {
            TagRequest r = create(UUID.randomUUID(), "t" + i, 3);
            manager.decide(r.getRequestId(), RequestStatus.APPROVED, DecisionActor.STAFF, "A", null, false);
        }
        assertEquals(80, manager.recentClosed(1000).size());

        // previously this only updated the field - the 80 entries stayed resident until enough
        // NEW close() calls ran, which on a quiet server is never
        manager.setClosedHistoryCap(50);
        assertEquals(50, manager.recentClosed(1000).size());
    }

    @Test
    void byTag_findsTheOpenRequestTrackingASpecificTagEntry() {
        UUID player = UUID.randomUUID();
        String tagId = UUID.randomUUID().toString();
        manager.create(player, "Tester", "<#Z>", tagId, "text", "text", "s", 3);

        assertTrue(manager.byTag(player, tagId).isPresent());
        assertTrue(manager.byTag(player, "other-tag").isEmpty());
        assertTrue(manager.byTag(UUID.randomUUID(), tagId).isEmpty());
    }
}
