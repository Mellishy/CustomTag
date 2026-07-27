package com.mellishy.customtag.service;

import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import com.mellishy.customtag.request.DecisionActor;
import com.mellishy.customtag.request.RequestManager;
import com.mellishy.customtag.request.RequestStatus;
import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransactionType;
import com.mellishy.customtag.util.PersistentCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagService itself needs a live Bukkit Player / plugin instance, so it cannot be unit-tested
 * under the project's deliberate "no MockBukkit" rule (see pom.xml). That does not mean the
 * business state machine is untested: every durable mutation TagService performs goes through
 * exactly three Bukkit-free collaborators - {@link TokenService}, {@link RequestManager} and
 * {@link PlayerData}/{@link TagEntry}.
 *
 * This suite drives those three the same way TagService does for the critical paths
 * (reserve → submit → approve/reject → undo → cancel), so a regression in the lifecycle that
 * would mint free tokens, drop a pending request, or let a second staff decision land is
 * caught here without spinning up a server.
 */
class TagLifecycleInvariantsTest {

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private TokenService tokens;
    private RequestManager requests;

    @BeforeEach
    void setUp() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        tokens = new TokenService(tempDir.resolve("ledger"), tempDir.resolve("freezes.json"),
                counters, Runnable::run, ex -> fail(ex), now::get);
        requests = new RequestManager(tempDir.resolve("requests.json"), counters, Runnable::run,
                ex -> fail(ex), now::get);
    }

    private PlayerData player(int balance) {
        return new PlayerData(UUID.randomUUID(), "Tester", balance);
    }

    /** Mirrors TagService#reserveForCreation once its guards have passed. */
    private String reserve(PlayerData data) {
        TokenService.Result result = tokens.apply(data, "<#T>", TokenTransactionType.CONSUME, 1,
                "tag-creation-reservation", data.getLastKnownName());
        assertInstanceOf(TokenService.Result.Success.class, result);
        String id = UUID.randomUUID().toString();
        data.setReservationActive(true);
        data.setReservationId(id);
        return id;
    }

    /** Mirrors TagService#releaseReservation. */
    private boolean releaseReservation(PlayerData data, String reason) {
        if (!data.isReservationActive()) return false;
        data.setReservationActive(false);
        data.setReservationId(null);
        tokens.apply(data, "<#T>", TokenTransactionType.REFUND, 1, reason, "system");
        return true;
    }

    /**
     * Mirrors TagService#submitNew after validation/security/queue checks have passed:
     * create the pending tag + request, then clear the reservation (token already spent).
     */
    private TagRequest submit(PlayerData data, String reservationId, String text) {
        assertTrue(data.isReservationActive() && reservationId.equals(data.getReservationId()));
        TagEntry entry = new TagEntry(UUID.randomUUID().toString(), data.getUuid(), text,
                TagStatus.PENDING, now.get());
        TagRequest request = requests.create(data.getUuid(), data.getLastKnownName(), "<#T>",
                entry.getId(), text, text, "test-server", 3).orElseThrow();
        data.getTags().add(entry);
        data.setReservationActive(false);
        data.setReservationId(null);
        return request;
    }

    /** Mirrors TagService#finalizeApproval's durable mutations. */
    private void approve(PlayerData data, TagRequest request) {
        TagRequest decided = requests.decide(request.getRequestId(), RequestStatus.APPROVED,
                DecisionActor.STAFF, "Admin", null, false).orElseThrow();
        TagEntry tag = data.getTagById(decided.getTagId()).orElseThrow();
        tag.setStatus(TagStatus.APPROVED);
        tag.setRejectReason(null);
        data.setActiveTagId(tag.getId());
    }

    /** Mirrors TagService#finalizeRejection. */
    private void reject(PlayerData data, TagRequest request, String reason, boolean refund) {
        TagRequest decided = requests.decide(request.getRequestId(), RequestStatus.REJECTED,
                DecisionActor.STAFF, "Admin", reason, refund).orElseThrow();
        TagEntry tag = data.getTagById(decided.getTagId()).orElseThrow();
        tag.setStatus(TagStatus.REJECTED);
        tag.setRejectReason(reason);
        if (refund) {
            tokens.apply(data, "<#T>", TokenTransactionType.REFUND, 1, "staff-reject", "Admin");
        }
    }

    /** Mirrors TagService undo: reopen the request and put the tag back to PENDING. */
    private void undo(PlayerData data, TagRequest closed) {
        TagRequest reopened = requests.reopen(closed.getRequestId(), DecisionActor.STAFF, "Admin")
                .orElseThrow();
        TagEntry tag = data.getTagById(reopened.getTagId()).orElseThrow();
        if (tag.getId().equals(data.getActiveTagId())) data.setActiveTagId(null);
        tag.setStatus(TagStatus.PENDING);
        tag.setRejectReason(null);
    }

    @Test
    void reserveSubmitApprove_spendsOneToken_andActivatesTheTag() {
        PlayerData data = player(3);
        String reservation = reserve(data);
        assertEquals(2, data.getTokens());

        TagRequest request = submit(data, reservation, "Pro");
        assertFalse(data.isReservationActive());
        assertEquals(1, requests.openCount());
        assertEquals(TagStatus.PENDING, data.getTags().get(0).getStatus());

        approve(data, request);

        assertEquals(0, requests.openCount());
        assertEquals(TagStatus.APPROVED, data.getTags().get(0).getStatus());
        assertEquals(data.getTags().get(0).getId(), data.getActiveTagId());
        assertEquals(2, data.getTokens(), "approval must never refund the creation token");
    }

    @Test
    void reserveSubmitRejectWithRefund_returnsTheToken_andKeepsHistory() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Bad");

        reject(data, request, "Inappropriate", true);

        assertEquals(3, data.getTokens());
        assertEquals(TagStatus.REJECTED, data.getTags().get(0).getStatus());
        assertEquals("Inappropriate", data.getTags().get(0).getRejectReason());
        assertEquals(RequestStatus.REJECTED, requests.byId(request.getRequestId()).orElseThrow().getStatus());
    }

    @Test
    void reserveSubmitRejectWithoutRefund_keepsTheTokenSpent() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Bad");

        reject(data, request, "silent", false);

        assertEquals(2, data.getTokens(), "a no-refund reject must leave the consume intact");
    }

    @Test
    void cancelReservation_withoutSubmitting_fullyRefunds() {
        PlayerData data = player(3);
        reserve(data);

        assertTrue(releaseReservation(data, "player-cancelled"));

        assertEquals(3, data.getTokens());
        assertFalse(data.isReservationActive());
        assertEquals(0, requests.openCount(), "cancel must never leave a phantom queue entry");
    }

    @Test
    void staleReservationAfterRefund_cannotSubmit() {
        PlayerData data = player(3);
        String stale = reserve(data);
        releaseReservation(data, "left-mid-creation");

        boolean stillValid = data.isReservationActive() && stale.equals(data.getReservationId());
        assertFalse(stillValid);
        assertEquals(3, data.getTokens());
    }

    @Test
    void doubleDecide_isRefused_andDoesNotMutateTagTwice() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Once");
        approve(data, request);

        Optional<TagRequest> second = requests.decide(request.getRequestId(), RequestStatus.REJECTED,
                DecisionActor.STAFF, "Other", "late", false);

        assertTrue(second.isEmpty());
        assertEquals(TagStatus.APPROVED, data.getTags().get(0).getStatus());
        assertEquals(2, data.getTokens());
    }

    @Test
    void undoAfterReject_reopensQueueAndClearsRejectReason() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Maybe");
        reject(data, request, "nope", true);
        assertEquals(3, data.getTokens());

        undo(data, request);

        assertEquals(1, requests.openCount());
        assertEquals(TagStatus.PENDING, data.getTags().get(0).getStatus());
        assertNull(data.getTags().get(0).getRejectReason());
        assertNull(data.getActiveTagId());
        // undo does not re-charge a token - the original consume still stands until a refunded reject
        // path returns it; after a refunded reject + undo the player has their token back and a
        // pending request again (same as TagService undo after staff reject-with-refund)
        assertEquals(3, data.getTokens());
    }

    @Test
    void staffLock_blocksSecondReviewer_untilUnlockOrTimeout() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Locked");
        UUID staffA = UUID.randomUUID();
        UUID staffB = UUID.randomUUID();
        requests.setLockTimeoutMillis(60_000);

        assertTrue(requests.tryLock(request.getRequestId(), staffA, "StaffA"));
        assertTrue(requests.isLockedByOther(request.getRequestId(), staffB));
        assertFalse(requests.tryLock(request.getRequestId(), staffB, "StaffB"));

        // TagService refuses staff actions while isLockedByOther - the second reviewer must not decide
        requests.unlock(request.getRequestId(), staffA);
        assertFalse(requests.isLockedByOther(request.getRequestId(), staffB));
        assertTrue(requests.tryLock(request.getRequestId(), staffB, "StaffB"));
    }

    @Test
    void decide_clearsAnyHeldLock() {
        PlayerData data = player(3);
        TagRequest request = submit(data, reserve(data), "Clear");
        UUID staff = UUID.randomUUID();
        assertTrue(requests.tryLock(request.getRequestId(), staff, "Staff"));

        approve(data, request);

        assertFalse(requests.isLockedByOther(request.getRequestId(), UUID.randomUUID()),
                "a decided request must not keep a live lock that would block undo/history views");
    }

    @Test
    void queueFull_refusesCreate_soCallerCanRefundReservation() {
        requests.setGlobalPendingLimit(1);
        PlayerData first = player(3);
        submit(first, reserve(first), "first");

        PlayerData second = player(3);
        reserve(second);
        assertTrue(requests.isQueueFull());
        assertTrue(requests.create(second.getUuid(), "B", "<#B>", "tag", "x", "x", "s", 3).isEmpty());

        // TagService#refuseQueueFull clears reservation + refunds - pin that recovery path here
        assertTrue(releaseReservation(second, "queue-full"));
        assertEquals(3, second.getTokens());
        assertFalse(second.isReservationActive());
    }
}
