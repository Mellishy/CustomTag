package com.mellishy.customtag.webhook;

import java.util.Map;

/**
 * Admin-editable message template for one event type (webhooks/messages.yml - "messages must
 * never be hardcoded"). {@code {placeholder}} tokens are replaced with the event's data.
 *
 * @param title Discord embed title / first Telegram line
 * @param body  multi-line body; placeholders like {player}, {tag}, {request_id}, {custom_id}
 * @param color Discord embed color as 0xRRGGBB int
 */
public record WebhookTemplate(String title, String body, int color) {

    /** Replaces every {key} token with its value; unknown tokens are left visible for debugging. */
    public static String apply(String template, Map<String, String> data) {
        if (template == null || template.isEmpty()) return "";
        String out = template;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
