package com.mellishy.customtag.ai;

/**
 * One pluggable AI backend - the moderation system is fully provider-independent.
 * Implementations are BLOCKING - they are only ever invoked on the AI service's dedicated
 * executor, never the main thread.
 */
public interface AIProvider {

    /** Stable name used in logs, audit entries and webhook payloads. */
    String name();

    /** Model id this provider is configured to use. */
    String model();

    /**
     * Asks the model to moderate one tag. {@code userContent} is the pre-built context (the tag
     * text plus any extra instructions); {@code systemPrompt} is the admin-editable prompt file.
     *
     * @throws Exception on any transport/parse failure - the AI service handles fallback.
     */
    AIDecision moderate(String systemPrompt, String userContent) throws Exception;
}
