package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagJson is the boundary between the in-memory model and every storage backend (MySQL TEXT
 * column, MongoDB field, YAML list). If this silently mangles data, every player's tag history
 * on a MySQL/Mongo server is at risk - so it's covered even though it has no Bukkit dependency
 * and is cheap to test in isolation.
 */
class TagJsonTest {

    private final UUID owner = UUID.randomUUID();

    @Test
    void serializeThenDeserialize_preservesCoreFields() {
        TagEntry original = new TagEntry("tag-1", owner, "<red>VIP</red>", TagStatus.APPROVED, 1_000L);

        String json = TagJson.serializeTags(List.of(original));
        List<TagEntry> restored = TagJson.deserializeTags(json, owner);

        assertEquals(1, restored.size());
        TagEntry entry = restored.get(0);
        assertEquals("tag-1", entry.getId());
        assertEquals("<red>VIP</red>", entry.getRawText());
        assertEquals(TagStatus.APPROVED, entry.getStatus());
        assertEquals(1_000L, entry.getCreatedAt());
        assertNull(entry.getRejectReason());
    }

    @Test
    void serializeThenDeserialize_preservesUpdatedAtSeparatelyFromCreatedAt() {
        // Regression test: deserializeTags used to always rebuild entries via the "brand-new tag"
        // constructor, which forces updatedAt = createdAt - silently discarding the real
        // last-modified time on every load. Fixed by using TagEntry's 6-arg reconstruction
        // constructor instead.
        TagEntry entry = new TagEntry("tag-1", owner, "text", TagStatus.PENDING, 1_000L);
        entry.setStatus(TagStatus.APPROVED); // bumps updatedAt via touch() to "now", well after createdAt

        TagEntry restored = TagJson.deserializeTags(TagJson.serializeTags(List.of(entry)), owner).get(0);

        assertEquals(entry.getCreatedAt(), restored.getCreatedAt());
        assertEquals(entry.getUpdatedAt(), restored.getUpdatedAt(),
                "updatedAt must survive a save/load cycle instead of resetting to createdAt");
        assertNotEquals(restored.getCreatedAt(), restored.getUpdatedAt());
    }

    @Test
    void serializeThenDeserialize_preservesRejectReason() {
        TagEntry rejected = new TagEntry("tag-2", owner, "bad word", TagStatus.REJECTED, 500L);
        rejected.setRejectReason("Inappropriate language");

        List<TagEntry> restored = TagJson.deserializeTags(TagJson.serializeTags(List.of(rejected)), owner);

        assertEquals("Inappropriate language", restored.get(0).getRejectReason());
    }

    @Test
    void deserialize_ofNullOrBlankJson_returnsEmptyListInsteadOfThrowing() {
        assertTrue(TagJson.deserializeTags(null, owner).isEmpty());
        assertTrue(TagJson.deserializeTags("", owner).isEmpty());
        assertTrue(TagJson.deserializeTags("   ", owner).isEmpty());
    }

    @Test
    void deserialize_ofCorruptStatusValue_failsSafeToRejectedInsteadOfThrowing() {
        // Simulates a row hand-edited in the DB, or a future version adding a new TagStatus that
        // an older jar doesn't know about yet - loading must never crash the whole plugin.
        String json = "[{\"id\":\"tag-3\",\"text\":\"weird\",\"status\":\"NOT_A_REAL_STATUS\",\"created\":1,\"updated\":1}]";

        List<TagEntry> restored = TagJson.deserializeTags(json, owner);

        assertEquals(1, restored.size());
        assertEquals(TagStatus.REJECTED, restored.get(0).getStatus());
    }

    @Test
    void deserialize_ofMalformedJson_returnsEmptyListInsteadOfThrowing() {
        assertTrue(TagJson.deserializeTags("{not valid json", owner).isEmpty());
    }

    @Test
    void pool_serializeThenDeserialize_preservesOrderAndContents() {
        List<String> pool = List.of("tag-a", "tag-b", "tag-c");

        List<String> restored = TagJson.deserializePool(TagJson.serializePool(pool));

        assertEquals(pool, restored);
    }

    @Test
    void pool_deserializeOfNullOrBlank_returnsEmptyListInsteadOfNull() {
        assertNotNull(TagJson.deserializePool(null));
        assertTrue(TagJson.deserializePool(null).isEmpty());
        assertTrue(TagJson.deserializePool("").isEmpty());
    }
}
