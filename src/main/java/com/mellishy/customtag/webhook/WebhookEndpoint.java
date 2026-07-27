package com.mellishy.customtag.webhook;

import java.util.Set;

/**
 * One configured external destination (webhooks/discord.yml, telegram.yml, custom.yml).
 *
 * @param name               admin-chosen identifier used in logs
 * @param kind               which wire format to speak
 * @param url                Discord webhook URL / generic POST URL (unused for Telegram)
 * @param telegramBotToken   Telegram bot token (TELEGRAM kind only)
 * @param telegramChatId     Telegram chat id (TELEGRAM kind only)
 * @param events             which events this endpoint receives
 * @param rateLimitPerMinute outbound message cap for this endpoint (0 = unlimited)
 */
public record WebhookEndpoint(String name, Kind kind, String url, String telegramBotToken,
                              String telegramChatId, Set<WebhookEventType> events,
                              int rateLimitPerMinute) {

    public enum Kind { DISCORD, TELEGRAM, GENERIC }

    public static WebhookEndpoint discord(String name, String url, Set<WebhookEventType> events, int rateLimit) {
        return new WebhookEndpoint(name, Kind.DISCORD, url, null, null, events, rateLimit);
    }

    public static WebhookEndpoint telegram(String name, String botToken, String chatId,
                                           Set<WebhookEventType> events, int rateLimit) {
        return new WebhookEndpoint(name, Kind.TELEGRAM, null, botToken, chatId, events, rateLimit);
    }

    public static WebhookEndpoint generic(String name, String url, Set<WebhookEventType> events, int rateLimit) {
        return new WebhookEndpoint(name, Kind.GENERIC, url, null, null, events, rateLimit);
    }

    /**
     * Redacted on purpose. A record's generated toString() prints every component, and three of
     * them are credentials: a Discord webhook URL and a Telegram bot token both grant full
     * post-as-this-integration access to anyone who reads them. One stray log line, stack trace or
     * debug print would put them in a console log that server owners routinely paste into
     * pastebins and issue reports.
     */
    @Override
    public String toString() {
        return "WebhookEndpoint[name=" + name + ", kind=" + kind
                + ", events=" + events + ", rateLimitPerMinute=" + rateLimitPerMinute + "]";
    }
}
