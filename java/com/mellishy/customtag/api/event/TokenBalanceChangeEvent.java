package com.mellishy.customtag.api.event;

import com.mellishy.customtag.token.TokenTransaction;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread after every token balance change, carrying the full ledger row
 * (transaction id, type, signed amount, balance after, actor). Store/website integrations can
 * mirror balances by listening here.
 */
public class TokenBalanceChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TokenTransaction transaction;

    public TokenBalanceChangeEvent(TokenTransaction transaction) {
        this.transaction = transaction;
    }

    public TokenTransaction getTransaction() { return transaction; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
