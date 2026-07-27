package com.mellishy.customtag.token;

/**
 * Every way a token balance may legally change - no system may edit balances outside
 * {@link TokenService}, and every change is one of these logged transaction types.
 */
public enum TokenTransactionType {
    /** Spent on a tag creation reservation. */
    CONSUME(-1),
    /** Returned after a refunded rejection / cancellation / expiry / failure. */
    REFUND(+1),
    /** Granted by staff ({@code /customtag give}). */
    ADMIN_GIVE(+1),
    /** Removed by staff ({@code /customtag take}). */
    ADMIN_TAKE(-1),
    /** Bought through a store/API integration. */
    PURCHASE(+1),
    /** Free promotional/reward tokens (votes, events, dailies). */
    REWARD(+1);

    private final int sign;

    TokenTransactionType(int sign) {
        this.sign = sign;
    }

    /** +1 for credit types, -1 for debit types - the ledger stores the signed delta. */
    public int sign() {
        return sign;
    }
}
