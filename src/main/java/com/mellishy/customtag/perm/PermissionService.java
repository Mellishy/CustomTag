package com.mellishy.customtag.perm;

import com.mellishy.customtag.module.ModuleConfigService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permissible;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

/**
 * Role-based permission layer. Two responsibilities:
 *
 * <ol>
 *   <li><b>Roles + per-role limits</b> - permissions/roles.yml defines roles (default, vip,
 *       staff, ...) granted by a Bukkit permission node, each with its own max-pending,
 *       max-tags, max-tag-length and queue priority. Inheritance is resolved at LOAD time
 *       (a {@code -1} value walks up the {@code inherits} chain), so runtime lookups are a
 *       flat, cheap scan.</li>
 *   <li><b>Granular staff actions</b> - {@link #canStaff} checks
 *       {@code customtag.staff.<action>} with the legacy umbrella {@code mellishy.admin} kept
 *       as a fallback, so existing setups keep working while large networks can hand out
 *       approve/reject/refund/undo/freeze/reload individually.</li>
 * </ol>
 *
 * Bukkit's permission system (and therefore LuckPerms etc.) stays the source of WHO has a node -
 * this service adds the role/limit semantics on top rather than running a parallel system.
 */
public class PermissionService {

    /** Umbrella node that grants every staff action - kept for backwards compatibility. */
    public static final String LEGACY_ADMIN = "mellishy.admin";

    /** Role values exactly as configured, before inheritance resolution (-1 = inherit). */
    private record RawRole(String name, int weight, String permission, String inherits,
                           int maxPending, int maxTags, int maxTagLength, int queuePriority) {}

    private final ModuleConfigService configs;
    private final Logger logger;

    /** Roles sorted by weight, highest first - resolution picks the first the player has. */
    private volatile List<RoleDefinition> rolesByWeight = List.of();
    private volatile RoleDefinition defaultRole =
            new RoleDefinition("default", 0, "", 1, -1, -1, 3);

    public PermissionService(ModuleConfigService configs, Logger logger) {
        this.configs = configs;
        this.logger = logger;
        reload();
    }

    /** Rebuilds role state from permissions/roles.yml (startup + module reload). */
    public void reload() {
        configs.reloadModule("permissions");
        YamlConfiguration cfg = configs.config("permissions", "roles.yml");
        String defaultName = cfg.getString("default-role", "default").toLowerCase(Locale.ROOT);

        Map<String, RawRole> raw = new HashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("roles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection r = section.getConfigurationSection(key);
                if (r == null) continue;
                String name = key.toLowerCase(Locale.ROOT);
                raw.put(name, new RawRole(name,
                        r.getInt("weight", 0),
                        r.getString("permission", ""),
                        r.getString("inherits", "").toLowerCase(Locale.ROOT),
                        r.getInt("max-pending", -1),
                        r.getInt("max-tags", -1),
                        r.getInt("max-tag-length", -1),
                        r.getInt("queue-priority", -1)));
            }
        }

        List<RoleDefinition> resolved = new ArrayList<>();
        for (RawRole r : raw.values()) {
            resolved.add(new RoleDefinition(r.name(), r.weight(), r.permission(),
                    resolveInherited(raw, r, RawRole::maxPending, 1),
                    resolveInherited(raw, r, RawRole::maxTags, -1),
                    resolveInherited(raw, r, RawRole::maxTagLength, -1),
                    resolveInherited(raw, r, RawRole::queuePriority, 3)));
        }
        resolved.sort(Comparator.comparingInt(RoleDefinition::weight).reversed());

        this.rolesByWeight = List.copyOf(resolved);
        this.defaultRole = resolved.stream()
                .filter(r -> r.name().equals(defaultName))
                .findFirst()
                .orElseGet(() -> {
                    if (!resolved.isEmpty()) {
                        logger.warning("[CustomTag] permissions/roles.yml has no '" + defaultName
                                + "' role - using built-in defaults for players with no role.");
                    }
                    return new RoleDefinition("default", 0, "", 1, -1, -1, 3);
                });
    }

    /**
     * Resolves one {@code -1} ("inherit") value by walking up the {@code inherits} chain,
     * with a depth cap so an accidental cycle in roles.yml can never hang the load.
     */
    private static int resolveInherited(Map<String, RawRole> raw, RawRole start,
                                        ToIntFunction<RawRole> getter, int fallback) {
        RawRole current = start;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            int value = getter.applyAsInt(current);
            if (value >= 0) return value;
            String parent = current.inherits();
            current = (parent == null || parent.isBlank()) ? null : raw.get(parent);
        }
        return fallback;
    }

    /**
     * The player's effective role: the highest-weight role whose permission node they hold,
     * falling back to the default role. Cheap (a scan over a handful of roles), so callers may
     * resolve on demand rather than caching.
     */
    public RoleDefinition roleOf(Permissible player) {
        for (RoleDefinition role : rolesByWeight) {
            if (role.permissionNode() == null || role.permissionNode().isBlank()) continue;
            if (player.hasPermission(role.permissionNode())) return role;
        }
        return defaultRole;
    }

    public RoleDefinition defaultRole() {
        return defaultRole;
    }

    /**
     * Granular staff-action check: {@code customtag.staff.<action>} OR the legacy umbrella
     * {@code mellishy.admin}. Actions in use: approve, reject, remove, refund, undo, freeze,
     * tokens, reload, audit, maintenance, queue.
     */
    public boolean canStaff(Permissible sender, String action) {
        return sender.hasPermission("customtag.staff." + action) || sender.hasPermission(LEGACY_ADMIN);
    }
}
