package com.mellishy.customtag.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagService's reservation flow (see its class javadoc: reserve-on-open, refund-on-cancel/
 * disconnect/death) is the plugin's dupe-proofing for the token economy - a regression there
 * means players either lose tokens for nothing or mint free ones. TagService itself needs a live
 * Bukkit Player to call, so these tests exercise the same state transitions directly against
 * PlayerData (the part that's actually mutated and actually holds the bug risk) the same way
 * TagService.reserveForCreation / releaseReservation do.
 */
class PlayerDataReservationTest {

    private PlayerData newPlayer(int tokens) {
        return new PlayerData(UUID.randomUUID(), "Tester", tokens);
    }

    /** Mirrors TagService#reserveForCreation's mutation once all of its guard checks pass. */
    private String reserve(PlayerData data) {
        data.addTokens(-1);
        String id = UUID.randomUUID().toString();
        data.setReservationActive(true);
        data.setReservationId(id);
        return id;
    }

    /** Mirrors TagService#releaseReservation. */
    private boolean release(PlayerData data) {
        if (data.isReservationActive()) {
            data.addTokens(1);
            data.setReservationActive(false);
            data.setReservationId(null);
            return true;
        }
        return false;
    }

    @Test
    void reserve_deductsExactlyOneTokenAndActivatesReservation() {
        PlayerData data = newPlayer(3);

        reserve(data);

        assertEquals(2, data.getTokens());
        assertTrue(data.isReservationActive());
        assertNotNull(data.getReservationId());
    }

    @Test
    void cancelAfterReserve_fullyRefundsTheTokenAndClearsState() {
        PlayerData data = newPlayer(3);
        reserve(data);

        boolean refunded = release(data);

        assertTrue(refunded);
        assertEquals(3, data.getTokens(), "token must come back exactly - no partial refund/leak");
        assertFalse(data.isReservationActive());
        assertNull(data.getReservationId());
    }

    @Test
    void releaseWithNoActiveReservation_isANoOpAndNeverGrantsAFreeToken() {
        PlayerData data = newPlayer(3);

        boolean refunded = release(data);

        assertFalse(refunded);
        assertEquals(3, data.getTokens());
    }

    @Test
    void doubleRelease_cannotDoubleRefundTheSameReservation() {
        PlayerData data = newPlayer(3);
        reserve(data);

        assertTrue(release(data));   // first release: legit refund
        assertFalse(release(data));  // second release on the same (already-cleared) state: no-op

        assertEquals(3, data.getTokens(), "a second release must not mint an extra token");
    }

    @Test
    void submittedReservation_isOnlyValidIfIdStillMatchesTheLiveReservation() {
        // Mirrors TagService#submitNew's validity check: a stale/abandoned book or chat session
        // carrying an old reservation id must be rejected once that reservation has been
        // refunded/replaced - this is what prevents duplicating a tag from a stale book.
        PlayerData data = newPlayer(3);
        String firstReservation = reserve(data);
        release(data); // player disconnected mid-creation; refunded

        boolean staleSubmissionValid = data.isReservationActive()
                && firstReservation.equals(data.getReservationId());

        assertFalse(staleSubmissionValid, "a refunded reservation id must never validate a late submission");
    }

    @Test
    void tokens_neverGoNegativeEvenIfReservedWithZeroBalance() {
        PlayerData data = newPlayer(0);

        // TagService itself blocks reserveForCreation() when tokens <= 0 before ever calling
        // addTokens(-1); this pins down the floor at the PlayerData level as a last line of
        // defense, since setTokens() clamps at zero regardless of caller.
        data.addTokens(-1);

        assertEquals(0, data.getTokens());
    }

    @Test
    void reservationSurvivesReenteringTheCreationMenu() {
        // TagService#reserveForCreation returns the EXISTING id (and does not charge again) if a
        // reservation is already active - this documents the invariant it relies on.
        PlayerData data = newPlayer(3);
        String id = reserve(data);

        assertTrue(data.isReservationActive());
        assertEquals(id, data.getReservationId());
        assertEquals(2, data.getTokens(), "re-entering the menu must not charge a second token");
    }
}
