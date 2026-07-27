package com.mellishy.customtag.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down the difference between TagEntry's two constructors, which is easy to get backwards:
 * the 5-arg one is ONLY for a brand-new tag (updatedAt must start equal to createdAt), the 6-arg
 * one is for reconstructing a tag loaded from storage (updatedAt must be restored as-is, not
 * recomputed) - see TagJson#deserializeTags and YamlStorageBackend#loadInternal, both of which
 * must use the 6-arg form.
 */
class TagEntryTest {

    private final UUID owner = UUID.randomUUID();

    @Test
    void newTagConstructor_startsWithUpdatedAtEqualToCreatedAt() {
        TagEntry entry = new TagEntry("id", owner, "text", TagStatus.PENDING, 1_000L);

        assertEquals(1_000L, entry.getCreatedAt());
        assertEquals(1_000L, entry.getUpdatedAt());
    }

    @Test
    void reconstructionConstructor_restoresUpdatedAtIndependentlyOfCreatedAt() {
        TagEntry entry = new TagEntry("id", owner, "text", TagStatus.APPROVED, 1_000L, 5_000L);

        assertEquals(1_000L, entry.getCreatedAt());
        assertEquals(5_000L, entry.getUpdatedAt());
    }

    @Test
    void setStatusOrSetRawText_bumpsUpdatedAtButNeverCreatedAt() {
        TagEntry entry = new TagEntry("id", owner, "text", TagStatus.PENDING, 1_000L, 1_000L);

        entry.setStatus(TagStatus.APPROVED);

        assertEquals(1_000L, entry.getCreatedAt(), "createdAt must never change after construction");
        assertTrue(entry.getUpdatedAt() >= 1_000L);
    }

    @Test
    void copy_preservesBothTimestampsIndependently() {
        TagEntry original = new TagEntry("id", owner, "text", TagStatus.APPROVED, 1_000L, 5_000L);

        TagEntry copy = original.copy();

        assertEquals(1_000L, copy.getCreatedAt());
        assertEquals(5_000L, copy.getUpdatedAt());
    }
}
