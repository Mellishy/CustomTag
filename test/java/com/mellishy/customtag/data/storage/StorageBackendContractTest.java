package com.mellishy.customtag.data.storage;

import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL/Mongo/YAML backends need a live broker or Bukkit's YamlConfiguration - neither belongs
 * in a unit suite. The contract DataManager relies on (init → save → load → saveAll → close,
 * deep copy semantics so a later mutation of the live PlayerData cannot corrupt what was
 * already persisted) is still enforceable against an in-memory implementation of the same
 * interface. If that contract drifts, every real backend will break the same way.
 */
class StorageBackendContractTest {

    private InMemoryStorageBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = new InMemoryStorageBackend();
        backend.init();
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void saveThenLoad_roundTripsTokensTagsAndReservation() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "Alice", 5);
        data.setReservationActive(true);
        data.setReservationId("res-1");
        TagEntry tag = new TagEntry("tag-1", uuid, "&aPro", TagStatus.APPROVED, 100L, 200L);
        data.getTags().add(tag);
        data.setActiveTagId("tag-1");

        backend.save(data);

        PlayerData loaded = backend.load(uuid).orElseThrow();
        assertEquals(5, loaded.getTokens());
        assertEquals("Alice", loaded.getLastKnownName());
        assertTrue(loaded.isReservationActive());
        assertEquals("res-1", loaded.getReservationId());
        assertEquals("tag-1", loaded.getActiveTagId());
        assertEquals(1, loaded.getTags().size());
        assertEquals("&aPro", loaded.getTags().get(0).getRawText());
        assertEquals(TagStatus.APPROVED, loaded.getTags().get(0).getStatus());
        assertEquals(200L, loaded.getTags().get(0).getUpdatedAt());
    }

    @Test
    void save_persistsASnapshot_laterMutationOfLiveObjectDoesNotLeak() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "Bob", 3);
        data.getTags().add(new TagEntry("t1", uuid, "one", TagStatus.PENDING, 1L));
        backend.save(data);

        // mutate the live object the same way the main thread would after queueing a save
        data.addTokens(10);
        data.getTags().get(0).setStatus(TagStatus.APPROVED);

        PlayerData loaded = backend.load(uuid).orElseThrow();
        assertEquals(3, loaded.getTokens(), "backend must have stored the snapshot, not a live reference");
        assertEquals(TagStatus.PENDING, loaded.getTags().get(0).getStatus());
    }

    @Test
    void loadAll_returnsEverySavedPlayer() throws Exception {
        PlayerData a = new PlayerData(UUID.randomUUID(), "A", 1);
        PlayerData b = new PlayerData(UUID.randomUUID(), "B", 2);
        backend.save(a);
        backend.save(b);

        Map<UUID, PlayerData> all = backend.loadAll();
        assertEquals(2, all.size());
        assertEquals(1, all.get(a.getUuid()).getTokens());
        assertEquals(2, all.get(b.getUuid()).getTokens());
    }

    @Test
    void saveAll_thenLoad_matchesIndividualSaves() throws Exception {
        PlayerData a = new PlayerData(UUID.randomUUID(), "A", 7);
        PlayerData b = new PlayerData(UUID.randomUUID(), "B", 9);
        backend.saveAll(java.util.List.of(a, b));

        assertEquals(7, backend.load(a.getUuid()).orElseThrow().getTokens());
        assertEquals(9, backend.load(b.getUuid()).orElseThrow().getTokens());
    }

    @Test
    void load_missingPlayer_returnsEmpty() throws Exception {
        assertTrue(backend.load(UUID.randomUUID()).isEmpty());
    }

    /**
     * Minimal StorageBackend that stores deep copies - the same snapshot discipline
     * {@link com.mellishy.customtag.data.DataManager#save} enforces before handing work to I/O.
     */
    static final class InMemoryStorageBackend implements StorageBackend {
        private final Map<UUID, PlayerData> store = new ConcurrentHashMap<>();

        @Override
        public void init() {}

        @Override
        public Map<UUID, PlayerData> loadAll() {
            Map<UUID, PlayerData> out = new HashMap<>();
            store.forEach((id, data) -> out.put(id, data.snapshot()));
            return out;
        }

        @Override
        public Optional<PlayerData> load(UUID uuid) {
            PlayerData data = store.get(uuid);
            return data == null ? Optional.empty() : Optional.of(data.snapshot());
        }

        @Override
        public void save(PlayerData data) {
            if (data == null) return;
            store.put(data.getUuid(), data.snapshot());
        }

        @Override
        public void saveAll(Collection<PlayerData> data) {
            data.forEach(this::save);
        }

        @Override
        public void close() {
            store.clear();
        }

        @Override
        public String name() {
            return "in-memory";
        }
    }
}
