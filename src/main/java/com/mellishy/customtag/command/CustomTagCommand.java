package com.mellishy.customtag.command;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.gui.AdminGUI;
import com.mellishy.customtag.gui.AdminPlayerTagsGUI;
import com.mellishy.customtag.gui.CreateMethodGUI;
import com.mellishy.customtag.gui.MainMenuGUI;
import com.mellishy.customtag.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class CustomTagCommand implements CommandExecutor, TabCompleter {

    private final MellishyCustomTag plugin;
    private final MainMenuGUI mainMenuGUI;
    private final AdminGUI adminGUI;
    private final CreateMethodGUI createMethodGUI;
    private final AdminPlayerTagsGUI adminPlayerTagsGUI;

    public CustomTagCommand(MellishyCustomTag plugin) {
        this.plugin = plugin;
        this.mainMenuGUI = new MainMenuGUI(plugin);
        this.adminGUI = new AdminGUI(plugin);
        this.createMethodGUI = new CreateMethodGUI(plugin);
        this.adminPlayerTagsGUI = new AdminPlayerTagsGUI(plugin);
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

        switch (args[0].toLowerCase()) {
            case "admin" -> {
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("player-only")));
                    return true;
                }
                adminGUI.open(player);
            }
            case "managetags" -> {
                if (!requireAdmin(sender)) return true;
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
            case "give", "take" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage("Usage: /customtag " + args[0] + " <player> <amount>");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("Amount must be a number.");
                    return true;
                }
                // Reject zero/negative amounts outright instead of silently flipping the operation's
                // sign (e.g. "take player -5" used to actually GIVE 5 tokens instead of erroring) -
                // confusing even for an admin-only command, and an easy typo to make under pressure.
                if (amount <= 0) {
                    sender.sendMessage("Amount must be a positive number.");
                    return true;
                }
                int signedAmount = args[0].equalsIgnoreCase("give") ? amount : -amount;
                String key = args[0].equalsIgnoreCase("give") ? "admin-gave-tokens" : "admin-took-tokens";
                String rawName = args[1];
                resolveOfflineTarget(rawName, target -> {
                    UUID uuid = target.getUniqueId();
                    PlayerData data = plugin.data().get(uuid, target.getName() != null ? target.getName() : rawName);
                    data.addTokens(signedAmount);
                    plugin.data().save(data);
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg(key)
                            .replace("{amount}", String.valueOf(amount))
                            .replace("{player}", rawName)));
                });
            }
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
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage("Usage: /customtag resetcooldown <player>");
                    return true;
                }
                String rawName = args[1];
                resolveOfflineTarget(rawName, target -> {
                    PlayerData data = plugin.data().get(target.getUniqueId(), target.getName() != null ? target.getName() : rawName);
                    plugin.cooldown().reset(data);
                    plugin.data().save(data);
                    sender.sendMessage(ColorUtil.parse(plugin.config().msg("admin-reset-cooldown").replace("{player}", rawName)));
                });
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                plugin.config().reload();
                plugin.guiStates().reload();
                // re-evaluate chat.auto-apply-tag and placeholders.enabled and actually register/
                // unregister their hooks to match - previously these two were only ever decided once
                // at plugin startup, so toggling them in config.yml silently required a full restart
                // even though this command claimed to have reloaded everything.
                plugin.reloadDynamicHooks();
                sender.sendMessage(ColorUtil.parse(plugin.config().msg("reload-success")));
            }
            default -> {
                if (sender instanceof Player player && requireUse(player)) {
                    mainMenuGUI.open(player);
                }
            }
        }
        return true;
    }

    /**
     * Resolves a target for /customtag give|take|resetcooldown WITHOUT ever calling the blocking
     * overload of {@link Bukkit#getOfflinePlayer(String)} on the main thread. That method's own
     * Javadoc warns it "may involve a blocking web request to get accurate information" for a name
     * the server has never seen locally - calling it straight from a command handler (main thread)
     * used to mean a single admin command could freeze the whole server for everyone while it waited
     * on Mojang's API. This checks the fast, non-blocking paths first (online player, then Paper's
     * local-cache-only {@code getOfflinePlayerIfCached}) and only falls back to the real blocking
     * lookup on a background thread, hopping back to the main thread afterwards to actually touch
     * player data - exactly the kind of "went out of its way to never freeze the server" fix a lead
     * would expect here.
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

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("mellishy.admin")) {
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("admin", "give", "take", "resetcooldown", "reload", "managetags")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && List.of("give", "take", "resetcooldown", "managetags").contains(args[0].toLowerCase())) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
