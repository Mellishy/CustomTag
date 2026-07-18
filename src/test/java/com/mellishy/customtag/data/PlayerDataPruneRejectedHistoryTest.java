package com.mellishy.customtag.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the fix for a real bug: with no cap, REJECTED tags accumulated in
 * {@link PlayerData#getTags()} forever - a player who submitted and got rejected repeatedly over a
 * long-running server's lifetime would carry an ever-growing, permanently-saved rejection history.
 * {@link PlayerData#pruneRejectedHistory(int)} (called from {@code TagService#reject} right after
 * every rejection) now keeps only the {@code cap} most recent rejected entries, identified by
 * {@link TagEntry#getUpdatedAt()} - PENDING/APPROVED tags must never be touched by this at all.
 */
class PlayerDataPruneRejectedHistoryTest {

    private final UUID owner = UUID.randomUUID();

    private TagEntry rejectedAt(long updatedAt) {
        return new TagEntry(UUID.randomUUID().toString(), owner, "text", TagStatus.REJECTED, updatedAt, updatedAt);
    }

    @Test
    void withinCap_nothingIsPruned() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        data.getTags().add(rejectedAt(1_000L));
        data.getTags().add(rejectedAt(2_000L));

        data.pruneRejectedHistory(5);

        assertEquals(2, data.getTags().size());
    }

    @Test
    void overCap_onlyOldestRejectedEntriesAreRemoved() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        data.getTags().add(rejectedAt(1_000L)); // oldest - should be pruned
        data.getTags().add(rejectedAt(2_000L)); // oldest - should be pruned
        data.getTags().add(rejectedAt(3_000L)); // kept
        data.getTags().add(rejectedAt(4_000L)); // kept
        data.getTags().add(rejectedAt(5_000L)); // kept

        data.pruneRejectedHistory(3);

        List<TagEntry> remaining = data.getTags();
        assertEquals(3, remaining.size());
        assertTrue(remaining.stream().allMatch(t -> t.getUpdatedAt() >= 3_000L),
                "only the 3 most recently rejected entries should survive");
    }

    @Test
    void capOfZero_disablesPruningEntirely() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        for (int i = 0; i < 50; i++) data.getTags().add(rejectedAt(i));

        data.pruneRejectedHistory(0);

        assertEquals(50, data.getTags().size(), "cap <= 0 must mean unlimited history, not zero");
    }

    @Test
    void pendingAndApprovedTags_areNeverPrunedRegardlessOfCap() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        TagEntry pending = new TagEntry(UUID.randomUUID().toString(), owner, "text", TagStatus.PENDING, 1L);
        TagEntry approved = new TagEntry(UUID.randomUUID().toString(), owner, "text", TagStatus.APPROVED, 1L);
        data.getTags().add(pending);
        data.getTags().add(approved);
        for (int i = 0; i < 10; i++) data.getTags().add(rejectedAt(i));

        data.pruneRejectedHistory(1);

        assertTrue(data.getTags().contains(pending));
        assertTrue(data.getTags().contains(approved));
        assertEquals(1, data.getTags().stream().filter(t -> t.getStatus() == TagStatus.REJECTED).count());
    }
}
