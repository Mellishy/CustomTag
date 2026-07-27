package com.mellishy.customtag.ai;

import com.mellishy.customtag.module.ModuleConfigService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Parses the ai/ module (settings.yml, providers.yml, prompts/moderation.txt) and configures
 * {@link AIModerationService}. Separate from the service so the service stays Bukkit-free.
 */
public final class AIConfigLoader {

    private AIConfigLoader() {}

    private static final String FALLBACK_PROMPT =
            "You moderate Minecraft player tags. Respond ONLY with JSON: "
                    + "{\"decision\":\"APPROVED|REJECTED|NEEDS_REVIEW\",\"confidence\":0-100,\"reason\":\"...\"}";

    public static void apply(AIModerationService service, ModuleConfigService configs,
                             HttpClient http, Logger logger) {
        configs.reloadModule("ai");
        YamlConfiguration settings = configs.config("ai", "settings.yml");

        AIModerationService.Mode mode;
        String rawMode = settings.getString("mode", "DISABLED");
        try {
            mode = AIModerationService.Mode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("[CustomTag] Unknown AI mode '" + rawMode + "' in ai/settings.yml - AI disabled.");
            mode = AIModerationService.Mode.DISABLED;
        }

        List<AIProvider> providers = new ArrayList<>();
        YamlConfiguration providersCfg = configs.config("ai", "providers.yml");
        ConfigurationSection section = providersCfg.getConfigurationSection("providers");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection p = section.getConfigurationSection(name);
                if (p == null || !p.getBoolean("enabled", false)) continue;
                String baseUrl = p.getString("base-url", "");
                String model = p.getString("model", "");
                if (baseUrl.isBlank() || model.isBlank()) {
                    logger.warning("[CustomTag] AI provider '" + name + "' is enabled but base-url/model is missing - skipped.");
                    continue;
                }
                providers.add(new OpenAiCompatibleProvider(
                        name, baseUrl,
                        p.getString("api-key", ""),
                        model,
                        Duration.ofSeconds(Math.max(3, p.getInt("timeout-seconds", 15))),
                        http));
            }
        }

        if (mode != AIModerationService.Mode.DISABLED && providers.isEmpty()) {
            logger.warning("[CustomTag] AI mode is " + mode + " but no provider in ai/providers.yml is enabled - AI stays inactive.");
        }

        String prompt = configs.textFile("ai", "prompts/moderation.txt", FALLBACK_PROMPT);

        service.configure(mode, providers, prompt,
                settings.getInt("confidence.approve-threshold", 85),
                settings.getInt("confidence.reject-threshold", 85),
                settings.getInt("limits.requests-per-minute", 20));
    }
}
