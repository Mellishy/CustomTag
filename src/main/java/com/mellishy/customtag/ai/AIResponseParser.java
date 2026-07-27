package com.mellishy.customtag.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Optional;

/**
 * Extracts a structured decision from whatever a chat model actually returned. Models are asked
 * (see ai/prompts/moderation.txt) to answer with strict JSON:
 * {@code {"decision":"APPROVED","confidence":95,"reason":"..."}} - but real models routinely
 * wrap it in markdown fences, prepend prose, or drop fields, so parsing is deliberately
 * forgiving: find the first JSON object anywhere in the text, then fall back to keyword
 * scanning, and only give up when neither works (the AI service treats that as a provider
 * failure and moves on to the fallback provider).
 *
 * Pure static logic - fully unit-testable without any network.
 */
public final class AIResponseParser {

    private AIResponseParser() {}

    private static final Gson GSON = new Gson();

    /** Parsed fields before provider/model/timing metadata is attached. */
    public record Parsed(AIDecisionType type, int confidence, String reason) {}

    public static Optional<Parsed> parse(String content) {
        if (content == null || content.isBlank()) return Optional.empty();

        Optional<Parsed> fromJson = parseJson(content);
        if (fromJson.isPresent()) return fromJson;

        return parseKeywords(content);
    }

    private static Optional<Parsed> parseJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        try {
            JsonObject obj = GSON.fromJson(content.substring(start, end + 1), JsonObject.class);
            if (obj == null || !obj.has("decision")) return Optional.empty();
            AIDecisionType type = matchType(obj.get("decision").getAsString());
            if (type == null) return Optional.empty();
            int confidence = -1;
            if (obj.has("confidence")) {
                try {
                    confidence = clampConfidence((int) Math.round(obj.get("confidence").getAsDouble()));
                } catch (Exception ignored) {
                    // a non-numeric confidence is dropped, not fatal
                }
            }
            String reason = obj.has("reason") ? obj.get("reason").getAsString() : null;
            return Optional.of(new Parsed(type, confidence, reason));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static Optional<Parsed> parseKeywords(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        // NEEDS_REVIEW first: "NEEDS_REVIEW" contains neither of the other keywords, but a model
        // saying "not APPROVED, needs review" must land on review, not approval
        if (upper.contains("NEEDS_REVIEW") || upper.contains("MANUAL_REVIEW") || upper.contains("NEEDS REVIEW")) {
            return Optional.of(new Parsed(AIDecisionType.NEEDS_REVIEW, -1, null));
        }
        boolean rejected = upper.contains("REJECTED") || upper.contains("REJECT");
        boolean approved = upper.contains("APPROVED") || upper.contains("APPROVE");
        if (rejected && !approved) return Optional.of(new Parsed(AIDecisionType.REJECTED, -1, null));
        if (approved && !rejected) return Optional.of(new Parsed(AIDecisionType.APPROVED, -1, null));
        return Optional.empty(); // ambiguous ("would approve but rejected") - treat as unparseable
    }

    private static AIDecisionType matchType(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (cleaned) {
            case "APPROVED", "APPROVE" -> AIDecisionType.APPROVED;
            case "REJECTED", "REJECT" -> AIDecisionType.REJECTED;
            case "NEEDS_REVIEW", "MANUAL_REVIEW", "REVIEW" -> AIDecisionType.NEEDS_REVIEW;
            default -> null;
        };
    }

    public static int clampConfidence(int confidence) {
        return Math.max(0, Math.min(100, confidence));
    }
}
