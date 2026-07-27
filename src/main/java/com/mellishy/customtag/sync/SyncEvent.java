package com.mellishy.customtag.sync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.Optional;

/**
 * One cross-server message: "token purchased", "tag approved", "queue updated", ... published
 * by the server where the action happened and consumed by every other server in the network so
 * caches stay identical everywhere.
 *
 * @param type       event kind, e.g. "request-approved", "token-transaction", "cache-invalidate"
 * @param serverName which server published it (network/settings.yml server-name)
 * @param data       string payload map (player uuid, request id, ...)
 */
public record SyncEvent(String type, String serverName, Map<String, String> data) {

    private static final Gson GSON = new Gson();

    /**
     * Parses an inbound Redis pub/sub payload the same way {@link RedisSyncService} does.
     *
     * Returns empty for: malformed JSON, a null/incomplete envelope, or an event that THIS server
     * itself published (Redis echoes every publish back to every subscriber on the channel -
     * without that filter a server would re-apply its own approve/reject and double-write the
     * cache). Pure and Bukkit-free so the echo-drop rule is unit-testable without a live broker.
     */
    public static Optional<SyncEvent> parseIncoming(String json, String thisServerName) {
        if (json == null || json.isBlank()) return Optional.empty();
        SyncEvent event;
        try {
            event = GSON.fromJson(json, SyncEvent.class);
        } catch (JsonSyntaxException ex) {
            return Optional.empty();
        }
        if (event == null || event.type() == null) return Optional.empty();
        if (thisServerName != null && thisServerName.equals(event.serverName())) return Optional.empty();
        return Optional.of(event);
    }

    /** True when {@code json} is non-blank but not valid SyncEvent JSON (used for throttled logging). */
    public static boolean isMalformedPayload(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            GSON.fromJson(json, SyncEvent.class);
            return false;
        } catch (JsonSyntaxException ex) {
            return true;
        }
    }

    /** Serializes this event for Redis publish - the inverse of {@link #parseIncoming}. */
    public String toJson() {
        return GSON.toJson(this);
    }
}
