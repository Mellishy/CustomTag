package com.mellishy.customtag.ai;

/**
 * One AI moderation verdict, complete with the audit metadata recorded on every AI decision
 * (provider, model, confidence, processing time, reason).
 *
 * @param type             the verdict
 * @param confidence       0-100 as reported by the model (-1 when it didn't provide one)
 * @param reason           the model's short explanation, shown to staff and logged
 * @param provider         provider name that produced this ("openai", "gemini-compat", ...)
 * @param model            model id used
 * @param processingMillis wall time of the API call
 */
public record AIDecision(AIDecisionType type, int confidence, String reason,
                         String provider, String model, long processingMillis) {}
