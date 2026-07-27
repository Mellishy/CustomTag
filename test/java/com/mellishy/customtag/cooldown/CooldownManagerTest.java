package com.mellishy.customtag.cooldown;

import com.mellishy.customtag.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CooldownManager has zero Bukkit dependency and gates whether a player can even attempt to
 * spend a token - a regression here either locks players out forever or lets them bypass the
 * cooldown entirely, so it's worth pinning down explicitly.
 */
class CooldownManagerTest {

    private final CooldownManager cooldown = new CooldownManager();

    private PlayerData newPlayer() {
        return new PlayerData(UUID.randomUUID(), "Tester", 3);
    }

    @Test
    void freshPlayer_isNotOnCooldown() {
        PlayerData data = newPlayer();
        assertFalse(cooldown.isOnCooldown(data));
        assertEquals(0, cooldown.remainingSeconds(data));
    }

    @Test
    void apply_putsPlayerOnCooldownForApproximatelyTheRequestedDuration() {
        PlayerData data = newPlayer();

        cooldown.apply(data, 60);

        assertTrue(cooldown.isOnCooldown(data));
        long remaining = cooldown.remainingSeconds(data);
        // allow a couple seconds of slack for test execution time
        assertTrue(remaining >= 58 && remaining <= 60, "expected ~60s remaining, got " + remaining);
    }

    @Test
    void reset_immediatelyClearsAnActiveCooldown() {
        PlayerData data = newPlayer();
        cooldown.apply(data, 120);
        assertTrue(cooldown.isOnCooldown(data));

        cooldown.reset(data);

        assertFalse(cooldown.isOnCooldown(data));
        assertEquals(0, cooldown.remainingSeconds(data));
    }

    @Test
    void remainingSeconds_neverGoesNegativeAfterCooldownExpires() {
        PlayerData data = newPlayer();
        // cooldown that already expired in the past
        data.setCooldownUntil(System.currentTimeMillis() - 5_000L);

        assertFalse(cooldown.isOnCooldown(data));
        assertEquals(0, cooldown.remainingSeconds(data));
    }

    @Test
    void formatDuration_underOneMinute_showsSecondsOnly() {
        assertEquals("45s", cooldown.formatDuration(45));
        assertEquals("0s", cooldown.formatDuration(0));
    }

    @Test
    void formatDuration_overOneMinute_showsMinutesAndSeconds() {
        assertEquals("2m 5s", cooldown.formatDuration(125));
        assertEquals("1m 0s", cooldown.formatDuration(60));
    }
}
