package com.mellishy.customtag.webhook;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mellishy.customtag.security.RateLimiter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The central integration layer: every outbound notification -
 * Discord embeds, Telegram messages, generic JSON POSTs to stores/panels - is dispatched
 * through here. Nothing anywhere else in the plugin talks to an external service directly.
 *
 * Guarantees:
 * <ul>
 *   <li><b>Fully async</b> - {@link #publish} returns immediately; all HTTP happens on a small
 *       dedicated scheduler, never the main thread.</li>
 *   <li><b>Retry with backoff</b> - failed deliveries retry (configurable attempts/delay); an
 *       endpoint being down never stops core plugin operation or loses queue state.</li>
 *   <li><b>Per-endpoint rate limits</b> - an over-budget delivery is rescheduled, not dropped.</li>
 *   <li><b>Templates, not hardcoded messages</b> - all text comes from webhooks/messages.yml.</li>
 * </ul>
 *
 * BUKKIT-FREE and unit-testable (HTTP client + logger injected).
 */
public class WebhookService {

    private static final Gson GSON = new Gson();

    /** How many times one delivery may be pushed back by its endpoint's rate limit before it is dropped. */
    private static final int MAX_RATE_LIMIT_DEFERRALS = 10;

    private final Logger logger;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "CustomTag-Webhook");
        t.setDaemon(true);
        return t;
    });

    private volatile List<WebhookEndpoint> endpoints = List.of();
    private volatile Map<WebhookEventType, WebhookTemplate> templates = Map.of();
    private volatile int maxAttempts = 3;
    private volatile long retryDelaySeconds = 10;
    private volatile String serverName = "server";

    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();

    /** Immutable statistics snapshot for /customtag stats. */
    public record Stats(long sent, long failed, long retried, int endpointCount) {}

    public WebhookService(Logger logger, HttpClient http) {
        this.logger = logger;
        this.http = http;
    }

    /** Applies parsed settings from the webhooks/ module (startup + reload). */
    public void configure(List<WebhookEndpoint> endpoints, Map<WebhookEventType, WebhookTemplate> templates,
                          int maxAttempts, long retryDelaySeconds, String serverName) {
        this.endpoints = List.copyOf(endpoints);
        this.templates = Map.copyOf(templates);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
        this.serverName = serverName;
        this.limiters.clear();
    }

    public boolean hasEndpoints() {
        return !endpoints.isEmpty();
    }

    /**
     * Broadcasts one event to every endpoint subscribed to it. Safe to call from the main
     * thread - returns immediately. {@code data} keys become the {placeholder} tokens of the
     * admin templates; tag text should already be color-stripped (emoji/symbols preserved) by
     * the caller, so Discord/Telegram never receive raw color codes.
     */
    public void publish(WebhookEventType event, Map<String, String> data) {
        List<WebhookEndpoint> current = endpoints;
        if (current.isEmpty()) return;
        Map<String, String> payload = new java.util.HashMap<>(data);
        payload.putIfAbsent("server", serverName);
        payload.putIfAbsent("event", event.name());
        for (WebhookEndpoint endpoint : current) {
            if (endpoint.events().contains(event)) {
                try {
                    scheduler.execute(() -> deliver(endpoint, event, payload, 1, 0));
                } catch (RejectedExecutionException ex) {
                    return; // shutting down - see schedule()
                }
            }
        }
    }

    private void deliver(WebhookEndpoint endpoint, WebhookEventType event,
                         Map<String, String> data, int attempt, int deferrals) {
        RateLimiter limiter = limiters.computeIfAbsent(endpoint.name(),
                k -> new RateLimiter(endpoint.rateLimitPerMinute(), 60_000L, System::currentTimeMillis));
        if (!limiter.tryAcquire(endpoint.name())) {
            // Bounded, unlike before: a rate-limited delivery used to reschedule itself forever
            // without counting the deferral, so an endpoint configured with a rate limit below its
            // real event rate accumulated scheduler tasks (each holding its own payload map) for
            // the entire uptime of the server - a slow but genuinely unbounded leak. Dropping the
            // oldest overflow is the right trade for a notification channel.
            if (deferrals >= MAX_RATE_LIMIT_DEFERRALS) {
                failed.incrementAndGet();
                logger.log(Level.WARNING, "[CustomTag] Webhook '" + endpoint.name() + "' is over its "
                        + "rate limit (" + endpoint.rateLimitPerMinute() + "/min) and event " + event
                        + " was dropped after " + deferrals + " deferrals. Raise rate-limit-per-minute "
                        + "for this endpoint or subscribe it to fewer events.");
                return;
            }
            long delay = Math.max(1, limiter.retryAfterSeconds(endpoint.name()));
            schedule(() -> deliver(endpoint, event, data, attempt, deferrals + 1), delay);
            return;
        }

        try {
            HttpRequest request = buildRequest(endpoint, event, data);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                sent.incrementAndGet();
                return;
            }
            throw new IllegalStateException("HTTP " + response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            if (attempt < maxAttempts) {
                retried.incrementAndGet();
                // linear backoff: delay, 2*delay, 3*delay ... enough for the "service briefly
                // down" case without holding messages for hours
                schedule(() -> deliver(endpoint, event, data, attempt + 1, deferrals),
                        retryDelaySeconds * attempt);
            } else {
                failed.incrementAndGet();
                logger.log(Level.WARNING, "[CustomTag] Webhook '" + endpoint.name() + "' failed after "
                        + maxAttempts + " attempts for event " + event + ": " + redact(endpoint, ex.getMessage()));
            }
        }
    }

    /** Schedules a retry, tolerating a scheduler that is already shutting down. */
    private void schedule(Runnable task, long delaySeconds) {
        try {
            scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ex) {
            // the server is stopping - an undelivered notification is not worth an error on the
            // way out, and the audit trail already recorded whatever this was announcing
        }
    }

    /**
     * Strips endpoint credentials out of a message before it reaches the console.
     *
     * Exception messages from the URI/HTTP layer routinely quote the URL they failed on, and for
     * two of the three endpoint kinds that URL IS the credential: a Discord webhook URL and the
     * Telegram bot token embedded in the API path both let anyone who reads the log post as that
     * integration. Server owners paste console output into issue reports constantly, so the
     * secret must never be in the string to begin with.
     */
    private static String redact(WebhookEndpoint endpoint, String message) {
        if (message == null) return "null";
        String out = message;
        if (endpoint.url() != null && !endpoint.url().isBlank()) {
            out = out.replace(endpoint.url(), "<redacted-url>");
        }
        if (endpoint.telegramBotToken() != null && !endpoint.telegramBotToken().isBlank()) {
            out = out.replace(endpoint.telegramBotToken(), "<redacted-token>");
        }
        return out;
    }

    private HttpRequest buildRequest(WebhookEndpoint endpoint, WebhookEventType event,
                                     Map<String, String> data) {
        WebhookTemplate template = templates.getOrDefault(event,
                new WebhookTemplate(event.name(), defaultBody(data), 0x5865F2));
        return switch (endpoint.kind()) {
            case DISCORD -> discordRequest(endpoint, event, template, data);
            case TELEGRAM -> telegramRequest(endpoint, template, data);
            case GENERIC -> genericRequest(endpoint, event, data);
        };
    }

    private HttpRequest discordRequest(WebhookEndpoint endpoint, WebhookEventType event,
                                       WebhookTemplate template, Map<String, String> data) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", WebhookTemplate.apply(template.title(), data));
        embed.addProperty("description", WebhookTemplate.apply(template.body(), data));
        embed.addProperty("color", template.color());
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "CustomTag \u2022 " + data.getOrDefault("server", serverName));
        embed.add("footer", footer);

        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);

        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
    }

    private HttpRequest telegramRequest(WebhookEndpoint endpoint, WebhookTemplate template,
                                        Map<String, String> data) {
        String text = WebhookTemplate.apply(template.title(), data) + "\n"
                + WebhookTemplate.apply(template.body(), data);
        String form = "chat_id=" + URLEncoder.encode(endpoint.telegramChatId(), StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + endpoint.telegramBotToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    private HttpRequest genericRequest(WebhookEndpoint endpoint, WebhookEventType event,
                                       Map<String, String> data) {
        JsonObject body = new JsonObject();
        body.addProperty("event", event.name());
        data.forEach(body::addProperty);
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
    }

    private String defaultBody(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        data.forEach((k, v) -> sb.append("**").append(k).append(":** ").append(v).append("\n"));
        return sb.toString();
    }

    public Stats stats() {
        return new Stats(sent.get(), failed.get(), retried.get(), endpoints.size());
    }

    /** Stops the dispatch scheduler - called from plugin shutdown. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
