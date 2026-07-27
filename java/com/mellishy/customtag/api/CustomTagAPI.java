package com.mellishy.customtag.api;

import com.mellishy.customtag.ai.AIModerationService;
import com.mellishy.customtag.audit.AuditLogService;
import com.mellishy.customtag.id.PlayerIdService;
import com.mellishy.customtag.perm.PermissionService;
import com.mellishy.customtag.request.RequestManager;
import com.mellishy.customtag.security.SecurityService;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.validation.ValidationService;
import com.mellishy.customtag.webhook.WebhookService;

/**
 * Public entry point for external plugins. Use it after CustomTag has enabled (in your own
 * onEnable with a {@code depend}/{@code softdepend} on CustomTag, or lazily on first use).
 *
 * Most integrations only need the high-level facade:
 *
 * <pre>{@code
 * if (CustomTagAPI.isAvailable()) {
 *     TagOperations ops = CustomTagAPI.operations();
 *     ops.applyTokens(uuid, TokenTransactionType.PURCHASE, 3, "store:webstore", "BuycraftBridge");
 *     ops.activeTagLegacy(uuid).ifPresent(tag -> ...);   // safe from any thread
 *     ops.approveRequest("REQ-000123", "MyDiscordBot");  // main thread only
 * }
 * }</pre>
 *
 * The full service layer stays available for deeper integrations - {@link #requests()},
 * {@link #tokens()}, {@link #playerIds()}, {@link #validation()}, {@link #ai()},
 * {@link #webhooks()}, {@link #audit()}, {@link #security()}, {@link #permissions()}.
 *
 * Or listen to the Bukkit events in {@code com.mellishy.customtag.api.event}:
 * {@code TagRequestCreatedEvent}, {@code TagRequestApprovedEvent}, {@code TagRequestRejectedEvent},
 * {@code AIDecisionEvent}, {@code TokenBalanceChangeEvent}.
 *
 * THREAD SAFETY: reads on every service are safe from any thread (they hand out copies /
 * immutable snapshots). MUTATIONS (token changes, request decisions) must run on the main
 * thread - same contract the plugin itself follows; {@link TagOperations} enforces it.
 *
 * ABUSE NOTE: there is deliberately no way to change a token balance or create a tag without
 * going through the validating, logged service methods - external plugins get the same rules
 * as the plugin's own GUI.
 */
public final class CustomTagAPI {

    private static volatile Services services;
    private static volatile TagOperations operations;

    /** Everything the API exposes, registered once by the plugin at startup. */
    public record Services(RequestManager requests, TokenService tokens, PlayerIdService playerIds,
                           ValidationService validation, AIModerationService ai,
                           WebhookService webhooks, AuditLogService audit,
                           SecurityService security, PermissionService permissions) {}

    private CustomTagAPI() {}

    /** Called by the plugin on enable/disable - not part of the public API surface. */
    public static void register(Services registered) {
        services = registered;
        if (registered == null) {
            operations = null;
        }
    }

    /** Called by the plugin once TagService exists - not part of the public API surface. */
    public static void registerOperations(TagOperations registered) {
        operations = registered;
    }

    /** True once CustomTag is enabled and the API is usable. */
    public static boolean isAvailable() {
        return services != null;
    }

    private static Services require() {
        Services s = services;
        if (s == null) {
            throw new IllegalStateException("CustomTag is not enabled (or was disabled) - check CustomTagAPI.isAvailable() first.");
        }
        return s;
    }

    /**
     * The high-level operations facade - active tag / balance / custom id lookups (any thread)
     * and token grants / request decisions (main thread) in single calls. Start here; drop down
     * to the individual services below only when you need something it doesn't cover.
     */
    public static TagOperations operations() {
        TagOperations ops = operations;
        if (ops == null) {
            throw new IllegalStateException("CustomTag is not enabled (or was disabled) - check CustomTagAPI.isAvailable() first.");
        }
        return ops;
    }

    /** Global request queue: read the pending queue, approve/reject/reopen, locks, history. */
    public static RequestManager requests() { return require().requests(); }

    /** The single token authority - every balance change is validated, atomic and ledger-logged. */
    public static TokenService tokens() { return require().tokens(); }

    /** Permanent per-player custom ids ({@code <#3VF-2>}) with reverse lookup. */
    public static PlayerIdService playerIds() { return require().playerIds(); }

    /** The pre-queue validation pipeline (blacklist, regex, unicode, reserved names). */
    public static ValidationService validation() { return require().validation(); }

    /** AI moderation orchestrator (mode, stats; moderation itself runs through the queue). */
    public static AIModerationService ai() { return require().ai(); }

    /** Outbound integration layer - publish custom events to configured endpoints. */
    public static WebhookService webhooks() { return require().webhooks(); }

    /** Central audit trail - append and search entries. */
    public static AuditLogService audit() { return require().audit(); }

    /** Rate limits, duplicate protection, security flags, maintenance mode. */
    public static SecurityService security() { return require().security(); }

    /** Role resolution and granular staff-action checks. */
    public static PermissionService permissions() { return require().permissions(); }
}
