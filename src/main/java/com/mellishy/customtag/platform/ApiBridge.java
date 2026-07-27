package com.mellishy.customtag.platform;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.api.TagOperations;
import com.mellishy.customtag.data.DataManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.request.DecisionActor;
import com.mellishy.customtag.request.TagRequest;
import com.mellishy.customtag.token.TokenService;
import com.mellishy.customtag.token.TokenTransactionType;
import com.mellishy.customtag.util.ColorUtil;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The implementation behind {@link com.mellishy.customtag.api.CustomTagAPI#operations()}.
 * Reads go through the thread-safe {@link DataManager.RenderSnapshot} so external plugins can
 * call them from async contexts (tab lists, scoreboards, web servers); mutations are forced
 * onto the main thread and routed through {@link com.mellishy.customtag.service.TagService} /
 * {@link PlatformServices} so an API caller can never skip validation, the ledger, the audit
 * trail or cross-server sync.
 */
public class ApiBridge implements TagOperations {

    private final MellishyCustomTag plugin;

    public ApiBridge(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    // ---- reads ----

    @Override
    public Optional<String> activeTagRaw(UUID player) {
        return Optional.ofNullable(plugin.data().renderSnapshot(player).activeTagRaw());
    }

    @Override
    public Optional<String> activeTagLegacy(UUID player) {
        // parseForOthers: an API consumer feeds this into ITS OWN output (tab, scoreboard, web) -
        // the same untrusted-to-others audience as chat, so no interactive click/hover components
        return activeTagRaw(player)
                .map(raw -> ColorUtil.toLegacyString(ColorUtil.parseForOthers(raw)));
    }

    @Override
    public int tokenBalance(UUID player) {
        return plugin.data().renderSnapshot(player).tokens();
    }

    @Override
    public int tagCount(UUID player) {
        return plugin.data().renderSnapshot(player).tagCount();
    }

    @Override
    public String customId(UUID player) {
        return plugin.platform().playerIds().idFor(player);
    }

    @Override
    public String customIdDisplay(UUID player) {
        return plugin.platform().playerIds().display(player);
    }

    @Override
    public Optional<UUID> playerByCustomId(String customId) {
        return plugin.platform().playerIds().byId(customId);
    }

    @Override
    public List<TagRequest> openRequests() {
        return plugin.platform().requests().openRequests();
    }

    @Override
    public Optional<TagRequest> requestById(String requestId) {
        return plugin.platform().requests().byId(requestId);
    }

    // ---- mutations ----

    @Override
    public TokenService.Result applyTokens(UUID player, TokenTransactionType type, int amount,
                                           String reason, String actorName) {
        requireMainThread("applyTokens");
        PlayerData data = plugin.tagService().loadTarget(player);
        TokenService.Result result = plugin.platform().applyTokens(data, type, amount, reason, actorName);
        if (result instanceof TokenService.Result.Success) {
            plugin.data().save(data);
        }
        return result;
    }

    @Override
    public boolean approveRequest(String requestId, String actorName) {
        requireMainThread("approveRequest");
        return plugin.tagService().approveRequestById(requestId, DecisionActor.API, actorName);
    }

    @Override
    public boolean rejectRequest(String requestId, String actorName, String reason, boolean refund) {
        requireMainThread("rejectRequest");
        return plugin.tagService().rejectRequestById(requestId, DecisionActor.API, actorName,
                reason == null || reason.isBlank() ? "rejected via API" : reason, refund);
    }

    private void requireMainThread(String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("CustomTagAPI." + operation + "() mutates live player "
                    + "data and must be called from the main server thread (use the scheduler).");
        }
    }
}
