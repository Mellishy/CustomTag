package com.mellishy.customtag.token;

import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.util.PersistentCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The token service is the single authority over every balance change - a bug here IS a
 * duplication/economy exploit, so the invariants (no negative balances, frozen accounts
 * blocked, sequential ledger ids, correct signs per transaction type) are pinned down.
 */
class TokenServiceTest {

    @TempDir
    Path tempDir;

    private TokenService tokens;

    @BeforeEach
    void setUp() {
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        tokens = new TokenService(tempDir.resolve("ledger"), tempDir.resolve("freezes.json"),
                counters, Runnable::run, ex -> fail(ex), System::currentTimeMillis);
    }

    private PlayerData player(int balance) {
        return new PlayerData(UUID.randomUUID(), "Tester", balance);
    }

    @Test
    void consume_takesOneToken_andWritesALedgerRowWithSequentialId() {
        PlayerData data = player(3);

        TokenService.Result result = tokens.apply(data, "<#0001-1>",
                TokenTransactionType.CONSUME, 1, "tag-creation", "Tester");

        assertInstanceOf(TokenService.Result.Success.class, result);
        TokenTransaction tx = ((TokenService.Result.Success) result).transaction();
        assertEquals("TOKEN-00000001", tx.transactionId());
        assertEquals(-1, tx.amount());
        assertEquals(2, tx.balanceAfter());
        assertEquals(2, data.getTokens());
    }

    @Test
    void debit_neverDrivesBalanceNegative() {
        PlayerData data = player(0);

        TokenService.Result result = tokens.apply(data, "<#X>",
                TokenTransactionType.CONSUME, 1, "tag-creation", "Tester");

        assertInstanceOf(TokenService.Result.InsufficientBalance.class, result);
        assertEquals(0, data.getTokens());
        assertEquals(0, tokens.totalTransactions(), "a refused transaction must not consume a ledger id");
    }

    @Test
    void zeroOrNegativeAmounts_areRejectedOutright() {
        PlayerData data = player(5);
        assertInstanceOf(TokenService.Result.InvalidAmount.class,
                tokens.apply(data, "<#X>", TokenTransactionType.ADMIN_GIVE, 0, "r", "Admin"));
        assertInstanceOf(TokenService.Result.InvalidAmount.class,
                tokens.apply(data, "<#X>", TokenTransactionType.ADMIN_TAKE, -5, "r", "Admin"));
        assertEquals(5, data.getTokens());
    }

    @Test
    void frozenAccount_blocksEveryBalanceChange_untilUnfrozen() {
        PlayerData data = player(3);
        tokens.freeze(data.getUuid(), "suspected duping");

        assertTrue(tokens.isFrozen(data.getUuid()));
        assertEquals("suspected duping", tokens.freezeReason(data.getUuid()).orElseThrow());
        assertInstanceOf(TokenService.Result.Frozen.class,
                tokens.apply(data, "<#X>", TokenTransactionType.ADMIN_GIVE, 5, "r", "Admin"));
        assertEquals(3, data.getTokens());

        assertTrue(tokens.unfreeze(data.getUuid()));
        assertFalse(tokens.unfreeze(data.getUuid()), "second unfreeze reports it wasn't frozen");
        assertInstanceOf(TokenService.Result.Success.class,
                tokens.apply(data, "<#X>", TokenTransactionType.ADMIN_GIVE, 5, "r", "Admin"));
        assertEquals(8, data.getTokens());
    }

    @Test
    void recentOf_returnsOnlyThatPlayersTransactions_newestFirst() {
        PlayerData a = player(3);
        PlayerData b = player(3);
        tokens.apply(a, "<#A>", TokenTransactionType.CONSUME, 1, "first", "A");
        tokens.apply(b, "<#B>", TokenTransactionType.CONSUME, 1, "other", "B");
        tokens.apply(a, "<#A>", TokenTransactionType.REFUND, 1, "second", "A");

        var recent = tokens.recentOf(a.getUuid(), 10);
        assertEquals(2, recent.size());
        assertEquals("second", recent.get(0).reason());
        assertEquals("first", recent.get(1).reason());
    }

    @Test
    void transactionTypes_applyTheCorrectSign() {
        PlayerData data = player(10);
        tokens.apply(data, "<#X>", TokenTransactionType.PURCHASE, 2, "store", "Store");
        assertEquals(12, data.getTokens());
        tokens.apply(data, "<#X>", TokenTransactionType.REWARD, 1, "vote", "System");
        assertEquals(13, data.getTokens());
        tokens.apply(data, "<#X>", TokenTransactionType.ADMIN_TAKE, 3, "cmd", "Admin");
        assertEquals(10, data.getTokens());
    }

    @Test
    void largeCredit_thatWouldOverflowInt_isRefusedAndLeavesBalanceUntouched() {
        PlayerData data = player(2_000_000_000);

        TokenService.Result result = tokens.apply(data, "<#X>",
                TokenTransactionType.ADMIN_GIVE, 2_000_000_000, "overflow", "Admin");

        assertInstanceOf(TokenService.Result.BalanceOverflow.class, result);
        assertEquals(2_000_000_000, data.getTokens(),
                "int overflow used to wrap the balance negative and Math.max(0,...) wiped it to zero");
        assertEquals(0, tokens.totalTransactions(), "a refused credit must not consume a ledger id");
    }

    @Test
    void freeze_survivesRestart() {
        PlayerData data = player(3);
        tokens.freeze(data.getUuid(), "suspected duping");
        tokens.flushNow();

        PersistentCounters counters2 = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        TokenService restarted = new TokenService(tempDir.resolve("ledger"), tempDir.resolve("freezes.json"),
                counters2, Runnable::run, ex -> fail(ex), System::currentTimeMillis);

        assertTrue(restarted.isFrozen(data.getUuid()));
        assertEquals("suspected duping", restarted.freezeReason(data.getUuid()).orElseThrow());
        assertInstanceOf(TokenService.Result.Frozen.class,
                restarted.apply(data, "<#X>", TokenTransactionType.ADMIN_GIVE, 1, "r", "Admin"));
    }
}
