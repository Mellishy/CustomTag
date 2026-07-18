package com.mellishy.customtag.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the fix for a real bug: {@code max-tags-per-player} used to be checked against
 * {@code getTags().size()}, which counts REJECTED tags too - a player with a handful of old
 * rejected attempts could get permanently blocked from ever creating another tag until they
 * happened to notice and manually delete the rejected history entries. {@link TagService} must
 * check {@link PlayerData#activeTagCount()} instead, everywhere the old size() check used to be
 * (both {@code reserveForCreation} and {@code canOpenCreateMethod} - they must agree, or the
 * create-method menu could claim creation is available and then get silently rejected).
 */
class PlayerDataActiveTagCountTest {

    private final UUID owner = UUID.randomUUID();

    private TagEntry tag(TagStatus status) {
        return new TagEntry(UUID.randomUUID().toString(), owner, "text", status, 1_000L);
    }

    @Test
    void rejectedTags_doNotCountTowardsActiveTagCount() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        data.getTags().add(tag(TagStatus.REJECTED));
        data.getTags().add(tag(TagStatus.REJECTED));
        data.getTags().add(tag(TagStatus.REJECTED));

        assertEquals(0, data.activeTagCount(), "rejected tags must never count against the max-tags cap");
        assertEquals(3, data.getTags().size(), "but they must still be kept as visible history");
    }

    @Test
    void pendingAndApprovedTags_doCountTowardsActiveTagCount() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        data.getTags().add(tag(TagStatus.PENDING));
        data.getTags().add(tag(TagStatus.APPROVED));
        data.getTags().add(tag(TagStatus.REJECTED));

        assertEquals(2, data.activeTagCount());
    }

    @Test
    void mixOfStatuses_onlyRejectedIsExcluded() {
        PlayerData data = new PlayerData(owner, "Tester", 5);
        for (int i = 0; i < 4; i++) data.getTags().add(tag(TagStatus.REJECTED));
        data.getTags().add(tag(TagStatus.APPROVED));

        assertEquals(1, data.activeTagCount());
        assertEquals(5, data.getTags().size());
    }
}
