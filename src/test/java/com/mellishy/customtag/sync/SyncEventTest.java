package com.mellishy.customtag.sync;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis itself is an external broker - spinning one up in unit tests would be integration
 * theatre. The durable correctness rule for cross-server sync lives in
 * {@link SyncEvent#parseIncoming}: drop malformed payloads and never re-apply this server's
 * own echo. That is what these tests pin down.
 */
class SyncEventTest {

    @Test
    void parseIncoming_acceptsForeignServerEvents() {
        SyncEvent published = new SyncEvent("request-approved", "lobby-1",
                Map.of("player", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

        Optional<SyncEvent> parsed = SyncEvent.parseIncoming(published.toJson(), "lobby-2");

        assertTrue(parsed.isPresent());
        assertEquals("request-approved", parsed.get().type());
        assertEquals("lobby-1", parsed.get().serverName());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", parsed.get().data().get("player"));
    }

    @Test
    void parseIncoming_dropsEchoOfThisServersOwnPublish() {
        SyncEvent own = new SyncEvent("token-transaction", "lobby-1", Map.of("amount", "1"));

        assertTrue(SyncEvent.parseIncoming(own.toJson(), "lobby-1").isEmpty(),
                "Redis echoes every publish back to every subscriber - without this filter a server "
                        + "would re-apply its own approve/reject and double-write the cache");
    }

    @Test
    void parseIncoming_rejectsMalformedAndIncompletePayloads() {
        assertTrue(SyncEvent.parseIncoming("{not-json", "lobby-1").isEmpty());
        assertTrue(SyncEvent.parseIncoming("", "lobby-1").isEmpty());
        assertTrue(SyncEvent.parseIncoming(null, "lobby-1").isEmpty());
        assertTrue(SyncEvent.parseIncoming("{\"serverName\":\"x\"}", "lobby-1").isEmpty(),
                "missing type must be treated as incomplete");
        assertTrue(SyncEvent.isMalformedPayload("{not-json"));
        assertFalse(SyncEvent.isMalformedPayload("{\"type\":\"ok\",\"serverName\":\"x\"}"));
    }

    @Test
    void roundTrip_preservesPayload() {
        SyncEvent original = new SyncEvent("cache-invalidate", "proxy", Map.of("uuid", "u-1"));
        SyncEvent restored = SyncEvent.parseIncoming(original.toJson(), "other").orElseThrow();
        assertEquals(original, restored);
    }
}
