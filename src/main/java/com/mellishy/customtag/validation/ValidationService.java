package com.mellishy.customtag.validation;

import com.mellishy.customtag.module.ModuleConfigService;
import com.mellishy.customtag.util.ColorUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The security gateway every submission passes BEFORE the queue and BEFORE any AI call:
 * unicode security -> character rules -> reserved names -> blacklist categories -> regex
 * rules, in that fixed priority order. The first non-ALLOW result wins.
 *
 * Running this first is also the cost optimizer for the AI system - a tag like "Owner" is
 * rejected here for free instead of spending an API call.
 *
 * The active pipeline is an immutable snapshot rebuilt by {@link #reload()}; {@link #validate}
 * is pure and thread-safe.
 */
public class ValidationService {

    private final ModuleConfigService configs;
    private final Logger logger;
    private volatile List<TagValidator> pipeline = List.of();
    private volatile String defaultRejectMessage = "&cThat tag is not allowed.";

    public ValidationService(ModuleConfigService configs, Logger logger) {
        this.configs = configs;
        this.logger = logger;
        reload();
    }

    /** Rebuilds the whole pipeline from the blacklist/ module configs. */
    public void reload() {
        configs.reloadModule("blacklist");
        List<TagValidator> validators = new ArrayList<>();

        YamlConfiguration settings = configs.config("blacklist", "settings.yml");
        this.defaultRejectMessage = settings.getString("default-reject-message", "&cThat tag is not allowed.");

        // 1. unicode security - always first (the AI must never be the first thing to see raw input)
        if (settings.getBoolean("unicode.block-invisible-characters", true)) {
            validators.add(new UnicodeValidator(
                    parseVerdict(settings.getString("unicode.action", "REJECT"), ValidationVerdict.REJECT),
                    settings.getBoolean("unicode.refund", true),
                    settings.getString("unicode.message", null)));
        }

        // 2. character rules
        validators.add(new CharacterRulesValidator(
                settings.getInt("characters.min-length", 1),
                settings.getString("characters.allowed-regex", ""),
                settings.getString("characters.too-short-message", null),
                settings.getString("characters.invalid-characters-message", null)));

        // 3. reserved names
        YamlConfiguration reserved = configs.config("blacklist", "reserved.yml");
        if (reserved.getBoolean("enabled", true)) {
            validators.add(new ReservedNamesValidator(
                    reserved.getStringList("exact"),
                    reserved.getStringList("contains"),
                    parseVerdict(reserved.getString("action", "REJECT"), ValidationVerdict.REJECT),
                    reserved.getBoolean("refund", true),
                    reserved.getString("message", null)));
        }

        // 4. blacklist word categories
        YamlConfiguration words = configs.config("blacklist", "words.yml");
        List<BlacklistValidator.Category> categories = new ArrayList<>();
        ConfigurationSection catSection = words.getConfigurationSection("categories");
        if (catSection != null) {
            for (String key : catSection.getKeys(false)) {
                ConfigurationSection cat = catSection.getConfigurationSection(key);
                if (cat == null || !cat.getBoolean("enabled", true)) continue;
                categories.add(BlacklistValidator.Category.of(
                        key,
                        parseVerdict(cat.getString("action", "REJECT"), ValidationVerdict.REJECT),
                        cat.getBoolean("refund", false),
                        "exact".equalsIgnoreCase(cat.getString("match", "contains"))
                                ? BlacklistValidator.MatchMode.EXACT
                                : BlacklistValidator.MatchMode.CONTAINS,
                        cat.getStringList("words"),
                        cat.getString("message", null)));
            }
        }
        if (!categories.isEmpty()) {
            validators.add(new BlacklistValidator(categories));
        }

        // 5. regex rules
        YamlConfiguration regex = configs.config("blacklist", "regex.yml");
        List<RegexRulesValidator.Rule> rules = new ArrayList<>();
        ConfigurationSection ruleSection = regex.getConfigurationSection("rules");
        if (ruleSection != null) {
            for (String key : ruleSection.getKeys(false)) {
                ConfigurationSection rule = ruleSection.getConfigurationSection(key);
                if (rule == null || !rule.getBoolean("enabled", true)) continue;
                String patternText = rule.getString("pattern", "");
                if (patternText.isBlank()) continue;
                try {
                    rules.add(new RegexRulesValidator.Rule(
                            key,
                            Pattern.compile(patternText),
                            parseTarget(rule.getString("target", "plain")),
                            parseVerdict(rule.getString("action", "REJECT"), ValidationVerdict.REJECT),
                            rule.getBoolean("refund", true),
                            rule.getString("message", null)));
                } catch (PatternSyntaxException ex) {
                    // one bad admin-written pattern must never disable the whole pipeline
                    logger.warning("[CustomTag] Invalid regex in blacklist/regex.yml rule '" + key
                            + "': " + ex.getMessage() + " - rule skipped.");
                }
            }
        }
        if (!rules.isEmpty()) {
            validators.add(new RegexRulesValidator(rules));
        }

        this.pipeline = List.copyOf(validators);
    }

    /**
     * Runs the full pipeline against one submission. Pure and thread-safe - callable from
     * anywhere, though in practice TagService calls it on the main thread before any token or
     * queue state is touched.
     */
    public ValidationResult validate(String playerName, String rawText) {
        String plain = ColorUtil.stripToPlain(rawText);
        ValidationInput input = new ValidationInput(playerName, rawText, plain, TextNormalizer.normalize(plain));
        for (TagValidator validator : pipeline) {
            ValidationResult result = validator.validate(input);
            if (!result.isAllowed()) {
                return result;
            }
        }
        return ValidationResult.allow();
    }

    /** Player-facing reason for a result, falling back to the module-wide default message. */
    public String messageFor(ValidationResult result) {
        return result.message() != null && !result.message().isBlank()
                ? result.message()
                : defaultRejectMessage;
    }

    private static ValidationVerdict parseVerdict(String raw, ValidationVerdict fallback) {
        if (raw == null) return fallback;
        try {
            return ValidationVerdict.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static RegexRulesValidator.Target parseTarget(String raw) {
        if (raw == null) return RegexRulesValidator.Target.PLAIN;
        try {
            return RegexRulesValidator.Target.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return RegexRulesValidator.Target.PLAIN;
        }
    }
}
