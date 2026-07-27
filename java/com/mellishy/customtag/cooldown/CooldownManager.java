package com.mellishy.customtag.cooldown;

import com.mellishy.customtag.data.PlayerData;

public class CooldownManager {

    public boolean isOnCooldown(PlayerData data) {
        return data.getCooldownUntil() > System.currentTimeMillis();
    }

    public long remainingSeconds(PlayerData data) {
        long remaining = data.getCooldownUntil() - System.currentTimeMillis();
        return Math.max(0, remaining / 1000L);
    }

    public void apply(PlayerData data, int seconds) {
        data.setCooldownUntil(System.currentTimeMillis() + (seconds * 1000L));
    }

    public void reset(PlayerData data) {
        data.setCooldownUntil(0L);
    }

    public String formatDuration(long totalSeconds) {
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}
