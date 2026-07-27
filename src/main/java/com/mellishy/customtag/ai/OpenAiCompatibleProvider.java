package com.mellishy.customtag.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Provider for every service speaking the OpenAI chat-completions wire format - which today
 * covers OpenAI itself, OpenRouter, Groq, DeepSeek, Ollama, Together, Gemini's compatibility
 * endpoint and most self-hosted gateways - full multi-provider support through a single,
 * battle-tested protocol. New providers are added purely in ai/providers.yml (name +
 * base-url + key + model), no code changes.
 *
 * BLOCKING by design - only ever called on the AI service's dedicated executor.
 */
public class OpenAiCompatibleProvider implements AIProvider {

    private static final Gson GSON = new Gson();

    /** Hard cap on how much of a provider's answer is buffered - see {@link #readBounded}. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient client;

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String model,
                                    Duration timeout, HttpClient client) {
        this.name = name;
        // accept both ".../v1" and ".../v1/" style base urls
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public AIDecision moderate(String systemPrompt, String userContent) throws Exception {
        long start = System.currentTimeMillis();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", 300);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userContent));
        body.add("messages", messages);

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readBounded(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI provider '" + name + "' returned HTTP "
                    + response.statusCode() + ": " + truncate(responseBody));
        }

        String content = extractContent(responseBody);
        var parsed = AIResponseParser.parse(content)
                .orElseThrow(() -> new IllegalStateException("AI provider '" + name
                        + "' returned an unparseable moderation answer: " + truncate(content)));

        return new AIDecision(parsed.type(), parsed.confidence(), parsed.reason(),
                name, model, System.currentTimeMillis() - start);
    }

    /**
     * Reads at most {@link #MAX_RESPONSE_BYTES} of the response.
     *
     * base-url points at whatever host the admin configured, which for self-hosted gateways and
     * free proxy endpoints is not necessarily a well-behaved one. BodyHandlers.ofString() would
     * buffer whatever that host chooses to send - a misbehaving or hostile endpoint answering a
     * moderation call with an endless stream is enough to OOM the server. A moderation verdict is
     * a few hundred bytes; anything past the cap is garbage regardless, so truncating and letting
     * the JSON parse fail degrades to staff review like every other provider failure.
     */
    private static String readBounded(InputStream body) throws IOException {
        try (InputStream in = body) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while (buffer.size() < MAX_RESPONSE_BYTES && (read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, Math.min(read, MAX_RESPONSE_BYTES - buffer.size()));
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    /**
     * Pulls the assistant text out of an OpenAI-shaped answer, failing with a message that says
     * what was actually wrong. Navigating this blind threw a bare NullPointerException the moment
     * a provider answered with anything unexpected (an error envelope, an HTML error page from a
     * proxy, an empty choices array), and AIModerationService only logs ex.getMessage() - which is
     * null for an NPE, so the console showed "provider 'x' failed (null)" and nothing else.
     */
    private String extractContent(String responseBody) {
        JsonObject root;
        try {
            root = GSON.fromJson(responseBody, JsonObject.class);
        } catch (JsonSyntaxException ex) {
            throw new IllegalStateException("AI provider '" + name + "' returned a non-JSON body: "
                    + truncate(responseBody));
        }
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray()
                || root.getAsJsonArray("choices").isEmpty()) {
            throw new IllegalStateException("AI provider '" + name
                    + "' returned no completion choices: " + truncate(responseBody));
        }
        JsonElement message = root.getAsJsonArray("choices").get(0).getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) {
            throw new IllegalStateException("AI provider '" + name
                    + "' returned a choice with no message: " + truncate(responseBody));
        }
        JsonElement content = message.getAsJsonObject().get("content");
        if (content == null || !content.isJsonPrimitive()) {
            throw new IllegalStateException("AI provider '" + name
                    + "' returned a message with no text content: " + truncate(responseBody));
        }
        return content.getAsString();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
