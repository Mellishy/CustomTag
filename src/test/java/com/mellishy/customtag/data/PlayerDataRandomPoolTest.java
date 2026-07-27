package com.mellishy.customtag.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * resolveRandomPool() decides what ChatTagListener is allowed to randomly show next to a
 * player's name - a regression here either leaks a rejected/deleted tag into chat or silently
 * breaks random mode for everyone who picked a subset. Pure logic, no Bukkit dependency.
 */
class PlayerDataRandomPoolTest {

    private final UUID owner = UUID.randomUUID();

    private TagEntry tag(String id, TagStatus status) {
        return new TagEntry(id, owner, "<white>" + id + "</white>", status, System.currentTimeMillis());
    }

    private PlayerData newPlayer() {
        return new PlayerData(owner, "Tester", 5);
    }

    @Test
    void noSubsetChosen_resolvesToEveryApprovedTag() {
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.getTags().add(tag("b", TagStatus.APPROVED));
        data.getTags().add(tag("c", TagStatus.PENDING)); // must be excluded

        List<TagEntry> pool = data.resolveRandomPool();

        assertEquals(2, pool.size());
        assertTrue(pool.stream().allMatch(t -> t.getStatus() == TagStatus.APPROVED));
    }

    @Test
    void explicitSubset_resolvesToOnlyTheChosenApprovedTags() {
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.getTags().add(tag("b", TagStatus.APPROVED));
        data.getTags().add(tag("c", TagStatus.APPROVED));
        data.getRandomTagPool().add("a");
        data.getRandomTagPool().add("c");

        List<TagEntry> pool = data.resolveRandomPool();

        assertEquals(2, pool.size());
        assertTrue(pool.stream().map(TagEntry::getId).toList().containsAll(List.of("a", "c")));
        assertFalse(pool.stream().anyMatch(t -> t.getId().equals("b")));
    }

    @Test
    void subsetThatBecameFullyInvalid_failsSafeToEveryApprovedTag() {
        // e.g. every tag the player picked for their subset was later deleted or rejected by an
        // admin - random mode must keep working with whatever they still have, not go silent.
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.getTags().add(tag("b", TagStatus.APPROVED));
        data.getRandomTagPool().add("now-deleted-tag-id");

        List<TagEntry> pool = data.resolveRandomPool();

        assertEquals(2, pool.size(), "must fall back to all approved tags, not return an empty pool");
    }

    @Test
    void subsetPartiallyInvalid_returnsOnlyTheStillValidEntries() {
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.getTags().add(tag("b", TagStatus.APPROVED));
        data.getRandomTagPool().add("a");
        data.getRandomTagPool().add("no-longer-exists");

        List<TagEntry> pool = data.resolveRandomPool();

        assertEquals(1, pool.size());
        assertEquals("a", pool.get(0).getId());
    }

    @Test
    void approvedTagCount_onlyCountsApprovedStatus() {
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.getTags().add(tag("b", TagStatus.PENDING));
        data.getTags().add(tag("c", TagStatus.REJECTED));
        data.getTags().add(tag("d", TagStatus.APPROVED));

        assertEquals(2, data.approvedTagCount());
    }

    @Test
    void getActiveTag_returnsEmptyIfTheActiveIdIsNoLongerApproved() {
        // e.g. an admin silently rejects a tag that was previously approved and active.
        PlayerData data = newPlayer();
        TagEntry t = tag("a", TagStatus.REJECTED);
        data.getTags().add(t);
        data.setActiveTagId("a");

        assertTrue(data.getActiveTag().isEmpty());
    }

    @Test
    void getActiveTag_returnsTheEntryWhenApprovedAndSelected() {
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));
        data.setActiveTagId("a");

        assertTrue(data.getActiveTag().isPresent());
        assertEquals("a", data.getActiveTag().get().getId());
    }

    @Test
    void hasPending_reflectsWhetherAnyTagIsCurrentlyPending() {
        PlayerData data = newPlayer();
        assertFalse(data.hasPending());

        data.getTags().add(tag("a", TagStatus.PENDING));

        assertTrue(data.hasPending());
        assertEquals("a", data.getPendingTag().get().getId());
    }

    @Test
    void snapshot_isFullyIndependentFromTheLiveInstance() {
        // The whole point of PlayerData#snapshot(): mutating the live object afterwards must
        // never be visible through a previously taken snapshot, since the snapshot may already be
        // mid-serialization on the I/O thread.
        PlayerData data = newPlayer();
        data.getTags().add(tag("a", TagStatus.APPROVED));

        PlayerData snapshot = data.snapshot();
        data.addTokens(-1);
        data.getTags().add(tag("b", TagStatus.APPROVED));
        data.getTags().get(0).setStatus(TagStatus.REJECTED);

        assertEquals(5, snapshot.getTokens(), "snapshot must not see the live token change");
        assertEquals(1, snapshot.getTags().size(), "snapshot must not see tags added afterwards");
        assertEquals(TagStatus.APPROVED, snapshot.getTags().get(0).getStatus(),
                "snapshot's copy of a TagEntry must not see later mutation of the live entry");
    }
}
