package com.mellishy.customtag.webhook;

import com.mellishy.customtag.module.ModuleConfigService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Parses the webhooks/ module configs (discord.yml, telegram.yml, custom.yml, messages.yml,
 * settings.yml) into the immutable endpoint + template state {@link WebhookService} runs on.
 * Kept separate from the service itself so the service stays Bukkit-free and unit-testable.
 */
public final class WebhookConfigLoader {

    private WebhookConfigLoader() {}

    /** Everything configure() needs, parsed in one pass. */
    public record Loaded(List<WebhookEndpoint> endpoints, Map<WebhookEventType, WebhookTemplate> templates,
                         int maxAttempts, long retryDelaySeconds) {}

    public static Loaded load(ModuleConfigService configs, Logger logger) {
        configs.reloadModule("webhooks");
        List<WebhookEndpoint> endpoints = new ArrayList<>();

        YamlConfiguration discord = configs.config("webhooks", "discord.yml");
        ConfigurationSection discordEndpoints = discord.getConfigurationSection("endpoints");
        if (discordEndpoints != null) {
            for (String name : discordEndpoints.getKeys(false)) {
                ConfigurationSection e = discordEndpoints.getConfigurationSection(name);
                if (e == null || !e.getBoolean("enabled", false)) continue;
                String url = e.getString("url", "");
                if (url.isBlank() || url.contains("REPLACE")) {
                    logger.warning("[CustomTag] Discord webhook '" + name + "' is enabled but its URL is not set - skipped.");
                    continue;
                }
                endpoints.add(WebhookEndpoint.discord(name, url,
                        parseEvents(e.getStringList("events"), logger, name),
                        e.getInt("rate-limit-per-minute", 25)));
            }
        }

        YamlConfiguration telegram = configs.config("webhooks", "telegram.yml");
        ConfigurationSection telegramEndpoints = telegram.getConfigurationSection("endpoints");
        if (telegramEndpoints != null) {
            for (String name : telegramEndpoints.getKeys(false)) {
                ConfigurationSection e = telegramEndpoints.getConfigurationSection(name);
                if (e == null || !e.getBoolean("enabled", false)) continue;
                String token = e.getString("bot-token", "");
                String chatId = e.getString("chat-id", "");
                if (token.isBlank() || token.contains("REPLACE") || chatId.isBlank()) {
                    logger.warning("[CustomTag] Telegram webhook '" + name + "' is enabled but bot-token/chat-id is not set - skipped.");
                    continue;
                }
                endpoints.add(WebhookEndpoint.telegram(name, token, chatId,
                        parseEvents(e.getStringList("events"), logger, name),
                        e.getInt("rate-limit-per-minute", 20)));
            }
        }

        YamlConfiguration custom = configs.config("webhooks", "custom.yml");
        ConfigurationSection customEndpoints = custom.getConfigurationSection("endpoints");
        if (customEndpoints != null) {
            for (String name : customEndpoints.getKeys(false)) {
                ConfigurationSection e = customEndpoints.getConfigurationSection(name);
                if (e == null || !e.getBoolean("enabled", false)) continue;
                String url = e.getString("url", "");
                if (url.isBlank() || url.contains("example.com")) {
                    logger.warning("[CustomTag] Custom webhook '" + name + "' is enabled but its URL is not set - skipped.");
                    continue;
                }
                endpoints.add(WebhookEndpoint.generic(name, url,
                        parseEvents(e.getStringList("events"), logger, name),
                        e.getInt("rate-limit-per-minute", 60)));
            }
        }

        Map<WebhookEventType, WebhookTemplate> templates = new EnumMap<>(WebhookEventType.class);
        YamlConfiguration messages = configs.config("webhooks", "messages.yml");
        ConfigurationSection templateSection = messages.getConfigurationSection("templates");
        if (templateSection != null) {
            for (String key : templateSection.getKeys(false)) {
                WebhookEventType event;
                try {
                    event = WebhookEventType.valueOf(key.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    logger.warning("[CustomTag] Unknown webhook template event '" + key + "' in webhooks/messages.yml - skipped.");
                    continue;
                }
                ConfigurationSection t = templateSection.getConfigurationSection(key);
                if (t == null) continue;
                templates.put(event, new WebhookTemplate(
                        t.getString("title", event.name()),
                        t.getString("body", ""),
                        parseColor(t.getString("color", "5865F2"))));
            }
        }

        YamlConfiguration settings = configs.config("webhooks", "settings.yml");
        return new Loaded(endpoints, templates,
                settings.getInt("retry.max-attempts", 3),
                settings.getLong("retry.delay-seconds", 10));
    }

    private static Set<WebhookEventType> parseEvents(List<String> raw, Logger logger, String endpointName) {
        Set<WebhookEventType> events = EnumSet.noneOf(WebhookEventType.class);
        for (String s : raw) {
            try {
                events.add(WebhookEventType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                logger.warning("[CustomTag] Unknown webhook event '" + s + "' on endpoint '" + endpointName + "' - skipped.");
            }
        }
        return events;
    }

    private static int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", "").replace("0x", ""), 16);
        } catch (NumberFormatException ex) {
            return 0x5865F2;
        }
    }
}
