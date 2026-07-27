package com.mellishy.customtag.request;

/**
 * WHO (or what) performed a queue action - stored on every request transition and shown in
 * Discord/webhook payloads as the "Approval Type" (AI Approved, Admin Approved,
 * API Approved, ...).
 */
public enum DecisionActor {
    PLAYER,
    STAFF,
    AI,
    CONSOLE,
    API,
    SYSTEM
}
