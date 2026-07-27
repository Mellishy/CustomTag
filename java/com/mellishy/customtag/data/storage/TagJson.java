package com.mellishy.customtag.data.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.data.TagStatus;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converts the "tags" list of a {@link PlayerData} to/from a single compact JSON string so it can
 * live in one TEXT column (MySQL) or one array field (MongoDB), instead of needing a second table /
 * sub-collection. Every server that can run this plugin (Paper/Spigot) already ships Gson internally
 * for its own use, so this adds zero extra jar weight.
 */
public final class TagJson {

    private TagJson() {}

    private static final Gson GSON = new Gson();

    private record Raw(String id, String text, String status, String reason, long created, long updated) {}

    public static String serializeTags(List<TagEntry> tags) {
        List<Raw> raws = new ArrayList<>();
        for (TagEntry t : tags) {
            raws.add(new Raw(t.getId(), t.getRawText(), t.getStatus().name(), t.getRejectReason(), t.getCreatedAt(), t.getUpdatedAt()));
        }
        return GSON.toJson(raws);
    }

    public static List<TagEntry> deserializeTags(String json, UUID owner) {
        List<TagEntry> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        List<Raw> raws;
        try {
            Type listType = new TypeToken<List<Raw>>() {}.getType();
            raws = GSON.fromJson(json, listType);
        } catch (JsonParseException | IllegalStateException ex) {
            // Malformed JSON in the DB (truncated row, wrong top-level type, hand-edited data)
            // must never crash tag loading - treat it as "no tags" and fail safe.
            return out;
        }
        if (raws == null) return out;
        for (Raw r : raws) {
            TagStatus status;
            try {
                status = TagStatus.valueOf(r.status());
            } catch (Exception ex) {
                status = TagStatus.REJECTED; // unknown/corrupt status - fail safe rather than crash loading
            }
            // use the 6-arg reconstruction constructor so the real updatedAt survives the load -
            // the 5-arg constructor is for brand-new tags only and would silently reset updatedAt
            // back to createdAt here, discarding the actual last-modified time on every restart.
            TagEntry entry = new TagEntry(r.id(), owner, r.text(), status, r.created(), r.updated());
            if (r.reason() != null) entry.setRejectReason(r.reason());
            out.add(entry);
        }
        return out;
    }

    public static String serializePool(List<String> pool) {
        return GSON.toJson(pool);
    }

    public static List<String> deserializePool(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> pool = GSON.fromJson(json, listType);
            return pool != null ? pool : new ArrayList<>();
        } catch (JsonParseException | IllegalStateException ex) {
            // same fail-safe as deserializeTags: corrupt pool data means an empty pool, not a crash
            return new ArrayList<>();
        }
    }
}
