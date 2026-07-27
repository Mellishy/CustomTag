package com.mellishy.customtag.command;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.ai.AIModerationService;
import com.mellishy.customtag.audit.AuditEntry;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.gui.AdminGUI;
import com.mellishy.customtag.gui.AdminPlayerTagsGUI;
import com.mellishy.customtag.gui.CreateMethodGUI;
import com.mellishy.customtag.gui.MainMenuGUI;
import com.mellishy.customtag.platform.PlatformServices;
import com.mellishy.customtag.request.RequestManager;
import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransaction;
import com.mellishy.customtag.token.TokenTransactionType;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.webhook.WebhookService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class CustomTagCommand implements CommandExecutor, TabCompleter {

    private final MellishyCustomTag plugin;
    private final MainMenuGUI mainMenuGUI;
    private final AdminGUI adminGUI;
    private final CreateMethodGUI createMethodGUI;
    private final AdminPlayerTagsGUI adminPlayerTagsGUI;

    private static final List<String> STAFF_SUBCOMMANDS = List.of(
            "admin", "give", "take", "resetcooldown", "reload", "managetags",
            "queue", "history", "audit", "undo", "freeze", "unfreeze",
            "maintenance", "pending", "stats", "tokens", "id");

    public CustomTagCommand(MellishyCustomTag plugin) {
        this.plugin = plugin;
        this.mainMenuGUI = new MainMenuGUI(plugin);
        this.adminGUI = new AdminGUI(plugin);
        this.createMethodGUI = new CreateMethodGUI(plugin);
        this.adminPlayerTagsGUI = new AdminPlayerTagsGUI(plugin);
    }

    private PlatformServices platform() {
        return plugin.platform();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                return true;
            }
            if (!requireUse(player)) return true;
            mainMenuGUI.open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin" -> {
                if (!requireStaff(sender, "queue")) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                adminGUI.open(player);
            }
            case "managetags" -> {
                if (!requireStaff(sender, "queue")) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /customtag managetags <player>");
                    return true;
                }
                String rawName = args[1];
                resolveOfflineTarget(rawName, target -> adminPlayerTagsGUI.open(player, target.getUniqueId()));
            }
            case "give", "take" -> handleGiveTake(sender, args);
            case "createnow" -> {
                // not advertised in tab-complete/usage - only ever triggered by clicking the
                // "you left mid-creation" chat message built in TagService#buildResumeMessage
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
                if (plugin.tagService().canOpenCreateMethod(data)) {
                    createMethodGUI.open(player, null, CreateMethodGUI.Origin.MAIN_MENU);
                } else {
                    mainMenuGUI.open(player);
                }
            }
            case "confirmcreate" -> {
                // not advertised in tab-complete/usage - only ever triggered by clicking
                // "(Click to create this)" in the chat preview built in ChatInputListener#showPreview
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                plugin.chatInput().confirmPreview(player);
            }
            case "cancelcreate" -> {
                // not advertised in tab-complete/usage - only ever triggered by clicking
                // "(Click to cancel)" in a chat or book preview (ChatInputListener#cancelPreview)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                plugin.chatInput().cancelPreview(player);
            }
            case "resetcooldown" -> {
                if (!requireStaff(sender, "cooldown")) return true;
                if (args.length < 2) {
                    sender.sendMessage("Usage: /customtag resetcooldown <player>");
                    return true;
                }
                String rawName = args[1];
                resolveOfflineTarget(rawName, target -> {
                    PlayerData data = loadTargetData(target.getUniqueId(), target.getName() != null ? target.getName() : rawName);
                    plugin.cooldown().reset(data);
                    plugin.data().save(data);
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("admin-reset-cooldown").replace("{player}", rawName)));
                });
            }
            case "reload" -> handleReload(sender, args);
            case "queue" -> handleQueue(sender);
            case "history" -> handleHistory(sender, args);
            case "audit" -> handleAudit(sender, args);
            case "undo" -> handleUndo(sender, args);
            case "freeze" -> handleFreeze(sender, args);
            case "unfreeze" -> handleUnfreeze(sender, args);
            case "maintenance" -> handleMaintenance(sender, args);
            case "pending" -> handlePending(sender, args);
            case "stats" -> handleStats(sender);
            case "tokens" -> handleTokens(sender, args);
            case "id" -> handleId(sender, args);
            default -> {
                if (sender instanceof Player player && requireUse(player)) {
                    mainMenuGUI.open(player);
                }
            }
        }
        return true;
    }

    // ---------- token economy (routed through the central, ledger-backed token service) ----------

    private void handleGiveTake(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "tokens")) return;
        if (args.length < 3) {
            sender.sendMessage("Usage: /customtag " + args[0] + " <player> <amount>");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("Amount must be a number.");
            return;
        }
        // Reject zero/negative amounts outright instead of silently flipping the operation's
        // sign (e.g. "take player -5" used to actually GIVE 5 tokens instead of erroring) -
        // confusing even for an admin-only command, and an easy typo to make under pressure.
        if (amount <= 0) {
            sender.sendMessage("Amount must be a positive number.");
            return;
        }
        boolean give = args[0].equalsIgnoreCase("give");
        String key = give ? "admin-gave-tokens" : "admin-took-tokens";
        String rawName = args[1];
        resolveOfflineTarget(rawName, target -> {
            UUID uuid = target.getUniqueId();
            PlayerData data = loadTargetData(uuid, target.getName() != null ? target.getName() : rawName);
            // routed through the central token service: validated, atomic, ledger-logged with a
            // TOKEN-XXXXXXXX id, audit-trailed, fires TokenBalanceChangeEvent + webhook
            TokenService.Result result = platform().applyTokens(data,
                    give ? TokenTransactionType.ADMIN_GIVE : TokenTransactionType.ADMIN_TAKE,
                    amount, "/customtag " + args[0] + " by " + sender.getName(), sender.getName());
            if (result instanceof TokenService.Result.Frozen) {
                sender.sendMessage(ColorUtil.parse("&cThat account is frozen ("
                        + platform().tokens().freezeReason(uuid).orElse("no reason") + ") - unfreeze it first."));
                return;
            }
            if (result instanceof TokenService.Result.InsufficientBalance ib) {
                sender.sendMessage(ColorUtil.parse("&cCannot take " + amount + " token(s) - "
                        + rawName + " only has " + ib.balance() + "."));
                return;
            }
            if (result instanceof TokenService.Result.BalanceOverflow bo) {
                sender.sendMessage(ColorUtil.parse("&cCannot give " + amount + " token(s) - "
                        + rawName + " already has " + bo.balance() + " and the balance would overflow."));
                return;
            }
            // Positive check, not a fall-through: the old code assumed "not frozen and not
            // insufficient" meant success, so any result added to TokenService.Result later would
            // have silently reported a balance change that never happened.
            if (!(result instanceof TokenService.Result.Success)) {
                sender.sendMessage(ColorUtil.parse("&cThe token change was refused - nothing was applied."));
                return;
            }
            plugin.data().save(data);
            sender.sendMessage(ColorUtil.parse(plugin.config().msg(key)
                    .replace("{amount}", String.valueOf(amount))
                    .replace("{player}", rawName)));
        });
    }

    // ---------- module-aware reload ----------

    private void handleReload(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "reload")) return;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all")) {
            String module = args[1].toLowerCase(Locale.ROOT);
            if (platform().reloadModule(module)) {
                sender.sendMessage(ColorUtil.parse("&aModule '&f" + module + "&a' reloaded."));
            } else {
                sender.sendMessage(ColorUtil.parse("&cUnknown module '&f" + module
                        + "&c'. Modules: &fblacklist, queue, tokens, security, logs, ai, webhooks, permissions, network"));
            }
            return;
        }
        plugin.config().reload();
        plugin.guiStates().reload();
        // re-evaluate chat.auto-apply-tag and placeholders.enabled and actually register/
        // unregister their hooks to match - previously these two were only ever decided once
        // at plugin startup, so toggling them in config.yml silently required a full restart
        // even though this command claimed to have reloaded everything.
        plugin.reloadDynamicHooks();
        platform().reloadAll();
        sender.sendMessage(ColorUtil.parse(plugin.config().msg("reload-success")));
    }

    // ---------- queue & history & audit ----------

    private void handleQueue(CommandSender sender) {
        if (!requireStaff(sender, "queue")) return;
        List<TagRequest> open = platform().requests().openRequests();
        sender.sendMessage(ColorUtil.parse("&8&m----------&r &bReview Queue &7(" + open.size()
                + "/" + (platform().requests().globalPendingLimit() <= 0 ? "\u221e" : platform().requests().globalPendingLimit())
                + ") &8&m----------"));
        if (open.isEmpty()) {
            sender.sendMessage(ColorUtil.parse("&7Nothing waiting for review."));
            return;
        }
        int shown = 0;
        for (TagRequest r : open) {
            if (shown++ >= 10) {
                sender.sendMessage(ColorUtil.parse("&7... and " + (open.size() - 10) + " more."));
                break;
            }
            String lock = r.getLockedByName() != null ? " &c[locked: " + r.getLockedByName() + "]" : "";
            String aiNote = r.getAiProvider() != null
                    ? " &d[AI " + r.getAiConfidence() + "%: " + (r.getAiReason() == null ? "-" : r.getAiReason()) + "]"
                    : "";
            sender.sendMessage(ColorUtil.parse("&f" + r.getRequestId()
                    + " &7" + r.getPlayerCustomId()
                    + " &b" + r.getPlayerName()
                    + " &8> &f" + r.getPlainText()
                    + " &7(" + r.getStatus() + ", p" + r.getPriority() + ")" + lock + aiNote));
        }
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "queue")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag history <player> [limit]");
            return;
        }
        int limit = args.length >= 3 ? parsePositive(args[2], 10) : 10;
        String rawName = args[1];
        SimpleDateFormat format = new SimpleDateFormat(plugin.config().adminDateFormat());
        resolveOfflineTarget(rawName, target -> {
            List<TagRequest> history = platform().requests().historyOf(target.getUniqueId(), limit);
            sender.sendMessage(ColorUtil.parse("&8&m----------&r &bRequest history: &f" + rawName
                    + " " + platform().playerIds().display(target.getUniqueId()) + " &8&m----------"));
            if (history.isEmpty()) {
                sender.sendMessage(ColorUtil.parse("&7No requests on record."));
                return;
            }
            for (TagRequest r : history) {
                String by = r.getDecidedByName() != null ? " &7by &f" + r.getDecidedByName() : "";
                String reason = r.getRejectReason() != null ? " &7(" + ColorUtil.stripToPlain(r.getRejectReason()) + ")" : "";
                sender.sendMessage(ColorUtil.parse("&f" + r.getRequestId()
                        + " &8[" + format.format(new java.util.Date(r.getCreatedAt())) + "]"
                        + " &b" + r.getPlainText()
                        + " &8> " + statusColor(r) + r.getStatus() + by + reason));
            }
        });
    }

    private String statusColor(TagRequest r) {
        return switch (r.getStatus()) {
            case APPROVED -> "&a";
            case REJECTED, REMOVED, EXPIRED -> "&c";
            case CANCELLED -> "&e";
            default -> "&6";
        };
    }

    private void handleAudit(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "audit")) return;
        String filter = args.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
        List<AuditEntry> entries = platform().audit().searchRecent(filter, null, 15);
        sender.sendMessage(ColorUtil.parse("&8&m----------&r &bAudit trail"
                + (filter.isBlank() ? "" : " &7(filter: &f" + filter + "&7)") + " &8&m----------"));
        if (entries.isEmpty()) {
            sender.sendMessage(ColorUtil.parse("&7No matching entries in the recent window."));
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat(plugin.config().adminDateFormat());
        for (AuditEntry e : entries) {
            sender.sendMessage(ColorUtil.parse("&8[" + format.format(new java.util.Date(e.at())) + "] "
                    + "&d" + e.category() + " &f" + e.action()
                    + " &7by &f" + (e.actorName() == null ? "-" : e.actorName())
                    + (e.targetName() != null ? " &7on &f" + e.targetName() : "")
                    + (e.requestId() != null ? " &7(" + e.requestId() + ")" : "")
                    + (e.detail() != null ? " &8- &7" + e.detail() : "")));
        }
    }

    private void handleUndo(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "undo")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag undo <request-id>   (find ids with /customtag history <player>)");
            return;
        }
        String requestId = args[1].toUpperCase(Locale.ROOT);
        if (plugin.tagService().undoRequest(sender, requestId)) {
            sender.sendMessage(ColorUtil.parse("&aRequest &f" + requestId
                    + " &ahas been reopened and is back in the review queue."));
        } else {
            sender.sendMessage(ColorUtil.parse("&cCould not reopen &f" + requestId
                    + "&c - unknown id, still open, or the queue is full."));
        }
    }

    // ---------- security & maintenance ----------

    private void handleFreeze(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "freeze")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag freeze <player> [reason...]");
            return;
        }
        String rawName = args[1];
        String reason = args.length >= 3
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : "frozen by " + sender.getName();
        resolveOfflineTarget(rawName, target -> {
            platform().tokens().freeze(target.getUniqueId(), reason);
            platform().audit().log(com.mellishy.customtag.audit.AuditCategory.SECURITY, "freeze",
                    sender.getName(), rawName, platform().playerIds().display(target.getUniqueId()), null, reason);
            sender.sendMessage(ColorUtil.parse("&eToken account of &f" + rawName + " &eis now &cFROZEN&e: &f" + reason));
        });
    }

    private void handleUnfreeze(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "freeze")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag unfreeze <player>");
            return;
        }
        String rawName = args[1];
        resolveOfflineTarget(rawName, target -> {
            if (platform().tokens().unfreeze(target.getUniqueId())) {
                platform().audit().log(com.mellishy.customtag.audit.AuditCategory.SECURITY, "unfreeze",
                        sender.getName(), rawName, platform().playerIds().display(target.getUniqueId()), null, null);
                sender.sendMessage(ColorUtil.parse("&aToken account of &f" + rawName + " &ais no longer frozen."));
            } else {
                sender.sendMessage(ColorUtil.parse("&7" + rawName + " wasn't frozen."));
            }
        });
    }

    private void handleMaintenance(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "maintenance")) return;
        if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            sender.sendMessage("Usage: /customtag maintenance <submissions|ai|webhooks> <on|off>"
                    + (platform().security().activeMaintenance().isEmpty()
                    ? "" : "   (currently frozen: " + String.join(", ", platform().security().activeMaintenance()) + ")"));
            return;
        }
        String subsystem = args[1].toLowerCase(Locale.ROOT);
        boolean enable = args[2].equalsIgnoreCase("on");
        boolean changed = enable
                ? platform().security().enableMaintenance(subsystem)
                : platform().security().disableMaintenance(subsystem);
        if (!changed) {
            sender.sendMessage(ColorUtil.parse("&7'" + subsystem + "' was already "
                    + (enable ? "under maintenance." : "active.")));
            return;
        }
        platform().audit().log(com.mellishy.customtag.audit.AuditCategory.SECURITY, "maintenance",
                sender.getName(), null, null, null, subsystem + " -> " + (enable ? "ON" : "OFF"));
        platform().webhooks().publish(com.mellishy.customtag.webhook.WebhookEventType.MAINTENANCE,
                java.util.Map.of("subsystem", subsystem, "state", enable ? "ON" : "OFF", "actor", sender.getName()));
        sender.sendMessage(ColorUtil.parse(enable
                ? "&eSubsystem '&f" + subsystem + "&e' is now &cFROZEN &e(maintenance mode)."
                : "&aSubsystem '&f" + subsystem + "&a' is active again."));
    }

    private void handlePending(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "queue")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag pending <limit>   (0 = unlimited; current: "
                    + platform().requests().globalPendingLimit() + ")");
            return;
        }
        int limit;
        try {
            limit = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("Limit must be a number (0 = unlimited).");
            return;
        }
        platform().setGlobalPendingLimit(Math.max(0, limit));
        platform().audit().log(com.mellishy.customtag.audit.AuditCategory.STAFF, "pending-limit",
                sender.getName(), null, null, null, String.valueOf(limit));
        sender.sendMessage(ColorUtil.parse("&aGlobal pending limit set to &f"
                + (limit <= 0 ? "unlimited" : limit) + "&a (saved to queue/settings.yml)."));
    }

    // ---------- stats & lookups ----------

    private void handleStats(CommandSender sender) {
        if (!requireStaff(sender, "stats")) return;
        RequestManager requests = platform().requests();
        AIModerationService.Stats ai = platform().ai().stats();
        WebhookService.Stats web = platform().webhooks().stats();
        sender.sendMessage(ColorUtil.parse("&8&m----------&r &bCustomTag statistics &8&m----------"));
        sender.sendMessage(ColorUtil.parse("&7Queue: &f" + requests.openCount() + " open &7/ &f"
                + requests.totalCreated() + " total requests ever &7(limit "
                + (requests.globalPendingLimit() <= 0 ? "unlimited" : requests.globalPendingLimit()) + ")"));
        sender.sendMessage(ColorUtil.parse("&7Tokens: &f" + platform().tokens().totalTransactions()
                + " ledger transactions"));
        sender.sendMessage(ColorUtil.parse("&7AI (&f" + platform().ai().mode() + "&7): &f" + ai.total()
                + " decisions &8(&a" + ai.approved() + " approved&8, &c" + ai.rejected()
                + " rejected&8, &6" + ai.needsReview() + " to staff&8) &7- &f" + ai.cacheHits()
                + " cache hits, &f" + ai.failures() + " failures, avg &f" + ai.averageLatencyMillis() + "ms"));
        sender.sendMessage(ColorUtil.parse("&7Webhooks: &f" + web.endpointCount() + " endpoints &7- &a"
                + web.sent() + " sent&7, &6" + web.retried() + " retried&7, &c" + web.failed() + " failed"));
        if (!platform().security().activeMaintenance().isEmpty()) {
            sender.sendMessage(ColorUtil.parse("&cMaintenance active: &f"
                    + String.join(", ", platform().security().activeMaintenance())));
        }
    }

    private void handleTokens(CommandSender sender, String[] args) {
        if (!requireStaff(sender, "tokens")) return;
        if (args.length < 2) {
            sender.sendMessage("Usage: /customtag tokens <player> [limit]");
            return;
        }
        int limit = args.length >= 3 ? parsePositive(args[2], 10) : 10;
        String rawName = args[1];
        SimpleDateFormat format = new SimpleDateFormat(plugin.config().adminDateFormat());
        resolveOfflineTarget(rawName, target -> {
            PlayerData data = loadTargetData(target.getUniqueId(), target.getName() != null ? target.getName() : rawName);
            String frozen = platform().tokens().isFrozen(target.getUniqueId())
                    ? " &c[FROZEN: " + platform().tokens().freezeReason(target.getUniqueId()).orElse("-") + "]" : "";
            sender.sendMessage(ColorUtil.parse("&8&m----------&r &bTokens: &f" + rawName
                    + " " + platform().playerIds().display(target.getUniqueId())
                    + " &7- balance &f" + data.getTokens() + frozen + " &8&m----------"));
            List<TokenTransaction> recent = platform().tokens().recentOf(target.getUniqueId(), limit);
            if (recent.isEmpty()) {
                sender.sendMessage(ColorUtil.parse("&7No transactions in the recent window (older ones are in logs/tokens/)."));
                return;
            }
            for (TokenTransaction tx : recent) {
                sender.sendMessage(ColorUtil.parse("&8[" + format.format(new java.util.Date(tx.at())) + "] "
                        + "&f" + tx.transactionId()
                        + (tx.amount() >= 0 ? " &a+" : " &c") + tx.amount()
                        + " &7(" + tx.type() + ") &8-> &f" + tx.balanceAfter()
                        + " &7by &f" + tx.actorName()
                        + (tx.reason() != null ? " &8- &7" + tx.reason() : "")));
            }
        });
    }

    private void handleId(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /customtag id <player | custom-id>");
                return;
            }
            if (!requireUse(player)) return;
            sender.sendMessage(ColorUtil.parse(plugin.config().msg("your-custom-id")
                    .replace("{id}", platform().playerIds().display(player.getUniqueId()))));
            return;
        }
        if (!requireStaff(sender, "queue")) return;
        String query = args[1];
        // reverse lookup first: "/customtag id 3VF-2" or "<#3VF-2>" resolves the owning player
        var byId = platform().playerIds().byId(query);
        if (byId.isPresent()) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(byId.get());
            sender.sendMessage(ColorUtil.parse("&7ID &f" + query.toUpperCase(Locale.ROOT)
                    + " &7belongs to &b" + (owner.getName() != null ? owner.getName() : byId.get())));
            return;
        }
        resolveOfflineTarget(query, target -> sender.sendMessage(ColorUtil.parse("&7Custom ID of &b"
                + query + "&7: &f" + platform().playerIds().display(target.getUniqueId()))));
    }

    private int parsePositive(String raw, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * BUGFIX: give/take/resetcooldown used to call {@code plugin.data().get(uuid, name)} directly,
     * which only checks the in-memory cache and silently fabricates a brand-new, blank
     * {@link PlayerData} (default starting tokens, no tags, no cooldown) if the target isn't
     * currently cached - exactly what happens to an offline player once
     * {@code storage.cache-eviction} (see {@link com.mellishy.customtag.data.DataManager#scheduleEviction})
     * has dropped them from memory. Saving that blank object right afterwards (as every one of
     * these commands does) would silently OVERWRITE the player's real backend record - their
     * actual tags, tokens and cooldown - with an empty one. {@code TagService#loadTarget} already
     * guards against exactly this for admin GUI actions by calling {@code ensureLoaded} first; this
     * helper applies the same guard here so every admin command that touches an offline player's
     * data goes through the same safe path. A no-op (single cheap map lookup) whenever
     * cache-eviction is disabled or the player was never evicted in the first place.
     */
    private PlayerData loadTargetData(UUID uuid, String nameIfNew) {
        plugin.data().ensureLoaded(uuid, nameIfNew);
        return plugin.data().get(uuid, nameIfNew);
    }

    /**
     * Resolves a target for admin commands WITHOUT ever calling the blocking overload of
     * {@link Bukkit#getOfflinePlayer(String)} on the main thread. That method's own Javadoc warns
     * it "may involve a blocking web request to get accurate information" for a name the server
     * has never seen locally - calling it straight from a command handler (main thread) used to
     * mean a single admin command could freeze the whole server for everyone while it waited on
     * Mojang's API. This checks the fast, non-blocking paths first (online player, then Paper's
     * local-cache-only {@code getOfflinePlayerIfCached}) and only falls back to the real blocking
     * lookup on a background thread, hopping back to the main thread afterwards to actually touch
     * player data.
     */
    private void resolveOfflineTarget(String rawName, Consumer<OfflinePlayer> onResolved) {
        Player online = Bukkit.getPlayerExact(rawName);
        if (online != null) {
            onResolved.accept(online);
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(rawName);
        if (cached != null) {
            onResolved.accept(cached);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer resolved = Bukkit.getOfflinePlayer(rawName);
            Bukkit.getScheduler().runTask(plugin, () -> onResolved.accept(resolved));
        });
    }

    /**
     * Granular staff permission: {@code customtag.staff.<action>}, with the legacy umbrella
     * {@code mellishy.admin} always accepted as a fallback (see PermissionService#canStaff) so
     * existing setups keep working unchanged while large networks can hand out approve/reject/
     * freeze/reload/... individually.
     */
    private boolean requireStaff(CommandSender sender, String action) {
        if (!platform().permissions().canStaff(sender, action)) {
            sender.sendMessage(ColorUtil.parse(plugin.config().msg("no-permission")));
            return false;
        }
        return true;
    }

    /**
     * BUGFIX: previously the ENTIRE /customtag command was gated behind mellishy.use at the
     * plugin.yml level, meaning a staff member who had mellishy.admin but NOT mellishy.use (e.g. an
     * admin account deliberately kept out of the player-facing menu) was silently locked out of
     * every admin subcommand too - admin and non-admin permissions were never actually independent.
     * mellishy.use is now checked explicitly, only for the player-facing entry points that actually
     * need it (opening the menu), so mellishy.admin alone is enough to use every admin subcommand.
     */
    private boolean requireUse(CommandSender sender) {
        if (!sender.hasPermission("mellishy.use")) {
            sender.sendMessage(ColorUtil.parse(plugin.config().msg("no-permission")));
            return false;
        }
        return true;
    }

    /**
     * Which staff permission each suggestible subcommand needs, so completion can only ever offer
     * what the sender is actually allowed to run.
     */
    private static final Map<String, String> SUBCOMMAND_PERMISSION = Map.ofEntries(
            Map.entry("admin", "queue"), Map.entry("managetags", "queue"), Map.entry("queue", "queue"),
            Map.entry("history", "queue"), Map.entry("undo", "undo"),
            Map.entry("give", "tokens"), Map.entry("take", "tokens"), Map.entry("tokens", "tokens"),
            Map.entry("freeze", "freeze"), Map.entry("unfreeze", "freeze"),
            Map.entry("audit", "audit"), Map.entry("stats", "stats"),
            Map.entry("maintenance", "maintenance"), Map.entry("pending", "queue"),
            Map.entry("resetcooldown", "cooldown"), Map.entry("reload", "reload"));

    /**
     * True when the sender may see/run this subcommand. Anything not in the permission map is a
     * player-facing subcommand (id, createnow, ...) gated by mellishy.use instead.
     */
    private boolean mayUse(CommandSender sender, String subcommand) {
        String action = SUBCOMMAND_PERMISSION.get(subcommand);
        return action == null
                ? sender.hasPermission("mellishy.use")
                : platform().permissions().canStaff(sender, action);
    }

    /**
     * Permission-filtered completion.
     *
     * This used to suggest every staff subcommand to everyone, and complete {@code /ct undo} with
     * live REQ-ids pulled straight from the queue - handing any player on the server a map of the
     * entire staff command surface plus real request identifiers to try it against. Completion is
     * now gated by exactly the same checks the command handlers enforce, so it can only ever
     * reveal what the sender could already run.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : STAFF_SUBCOMMANDS) {
                if (s.startsWith(prefix) && mayUse(sender, s)) out.add(s);
            }
            return out;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!mayUse(sender, subcommand)) return out;

        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            switch (subcommand) {
                case "give", "take", "resetcooldown", "managetags", "history",
                        "freeze", "unfreeze", "tokens", "id" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                    }
                }
                case "reload" -> {
                    for (String s : List.of("all", "blacklist", "queue", "tokens", "security",
                            "logs", "ai", "webhooks", "permissions", "network")) {
                        if (s.startsWith(prefix)) out.add(s);
                    }
                }
                case "maintenance" -> {
                    for (String s : List.of("submissions", "ai", "webhooks")) {
                        if (s.startsWith(prefix)) out.add(s);
                    }
                }
                case "undo" -> {
                    // recently closed requests are the ones staff realistically want to undo
                    for (TagRequest r : platform().requests().recentClosed(10)) {
                        if (r.getRequestId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            out.add(r.getRequestId());
                        }
                    }
                }
                default -> { }
            }
        } else if (args.length == 3 && subcommand.equals("maintenance")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (String s : List.of("on", "off")) {
                if (s.startsWith(prefix)) out.add(s);
            }
        }
        return out;
    }
}
