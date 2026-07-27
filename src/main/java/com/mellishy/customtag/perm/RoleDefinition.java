package com.mellishy.customtag.perm;

/**
 * One role from permissions/roles.yml with its per-role limits: max pending requests (e.g.
 * default players 1, VIP 2, staff unlimited), per-rank tag length and queue priority.
 * A value of {@code -1} means "inherit" - resolved against the parent role chain at
 * load time, falling back to the plugin-wide defaults from config.yml.
 *
 * @param name           role id (lowercase)
 * @param weight         higher weight wins when a player qualifies for several roles
 * @param permissionNode Bukkit permission that grants this role ("" = everyone)
 * @param maxPending     open requests this role may have queued at once (0 = unlimited)
 * @param maxTags        overrides tokens.max-tags-per-player (-1 = plugin default)
 * @param maxTagLength   overrides tokens.max-tag-length (-1 = plugin default)
 * @param queuePriority  review priority (lower = reviewed first)
 */
public record RoleDefinition(String name, int weight, String permissionNode, int maxPending,
                             int maxTags, int maxTagLength, int queuePriority) {}
