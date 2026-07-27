package com.mellishy.customtag.id;

import com.mellishy.customtag.util.PersistentCounters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permanent custom player ids are referenced from queue entries, ledgers, audit logs and
 * Discord embeds - once handed out, an id may never change, collide or be lost on restart.
 */
class PlayerIdServiceTest {

    @TempDir
    Path tempDir;

    private PlayerIdService newService(PersistentCounters counters) {
        return new PlayerIdService(tempDir.resolve("ids.json"), counters, Runnable::run, ex -> fail(ex));
    }

    @Test
    void idFor_isStablePerPlayer_andUniqueAcrossPlayers() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        PlayerIdService ids = newService(counters);

        UUID player = UUID.randomUUID();
        String id = ids.idFor(player);
        assertEquals(id, ids.idFor(player), "asking twice must never mint a second id");

        Set<String> seen = new HashSet<>();
        seen.add(id);
        for (int i = 0; i < 200; i++) {
            assertTrue(seen.add(ids.idFor(UUID.randomUUID())), "duplicate id minted");
        }
    }

    @Test
    void byId_reverseLookup_acceptsEveryDisplayedForm() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        PlayerIdService ids = newService(counters);
        UUID player = UUID.randomUUID();
        String raw = ids.idFor(player);

        assertEquals(player, ids.byId(raw).orElseThrow());
        assertEquals(player, ids.byId("<#" + raw + ">").orElseThrow(), "displayed form must resolve");
        assertEquals(player, ids.byId(raw.toLowerCase()).orElseThrow(), "lookup is case-insensitive");
        assertTrue(ids.byId("ZZZ-9").isEmpty());
        assertTrue(ids.byId(null).isEmpty());
    }

    @Test
    void display_wrapsTheIdInTheSpecFormat() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        PlayerIdService ids = newService(counters);
        UUID player = UUID.randomUUID();

        String display = ids.display(player);
        assertTrue(display.startsWith("<#") && display.endsWith(">"), display);
        assertTrue(display.contains("-"), "spec format keeps a dash before the last digit: " + display);
    }

    @Test
    void ids_surviveRestart_andTheCounterNeverReusesNumbers() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        PlayerIdService ids = newService(counters);
        UUID player = UUID.randomUUID();
        String id = ids.idFor(player);
        ids.flushNow();
        counters.flushNow();

        PersistentCounters counters2 = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        PlayerIdService restarted = newService(counters2);

        assertEquals(id, restarted.idFor(player), "existing id must survive the restart");
        assertNotEquals(id, restarted.idFor(UUID.randomUUID()), "new players get new ids after restart");
    }

    @Test
    void format_usesCrockfordAlphabet_withoutAmbiguousLetters() {
        // 4-char padding with the trailing dash split, matching the display format (<#3VF-2>)
        assertEquals("000-1", PlayerIdService.format(1));
        assertEquals("000-Z", PlayerIdService.format(31));
        assertEquals("001-0", PlayerIdService.format(32));
        // the Crockford alphabet skips I, L, O and U entirely
        for (long v : new long[]{1, 31, 32, 1024, 32_768, 1_000_000}) {
            String formatted = PlayerIdService.format(v);
            assertFalse(formatted.matches(".*[ILOU].*"), formatted + " contains an ambiguous letter");
        }
    }
}
