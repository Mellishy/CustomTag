package com.mellishy.customtag.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Sliding-window limiter shared by submissions, webhooks and AI spend caps. */
class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void allowsUpToTheBudget_thenBlocks_thenRecoversAsEventsLeaveTheWindow() {
        RateLimiter limiter = new RateLimiter(2, 10_000L, now::get);

        assertTrue(limiter.tryAcquire("key"));
        now.addAndGet(4_000);
        assertTrue(limiter.tryAcquire("key"));
        assertFalse(limiter.tryAcquire("key"));

        // the FIRST event leaves the window at t0+10s - one slot frees up, not both
        now.addAndGet(6_500);
        assertTrue(limiter.tryAcquire("key"));
        assertFalse(limiter.tryAcquire("key"));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 10_000L, now::get);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        assertFalse(limiter.tryAcquire("a"));
    }

    @Test
    void zeroOrNegativeLimit_meansUnlimited() {
        RateLimiter limiter = new RateLimiter(0, 10_000L, now::get);
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("key"));
        }
    }

    @Test
    void retryAfterSeconds_roundsUp_andHitsZeroOnceTheWindowPasses() {
        RateLimiter limiter = new RateLimiter(1, 10_000L, now::get);
        assertTrue(limiter.tryAcquire("key"));

        now.addAndGet(4_500);
        // 5.5s remaining -> reported as 6 (ceil), never 5 (telling players a too-short wait is worse)
        assertEquals(6, limiter.retryAfterSeconds("key"));

        now.addAndGet(6_000);
        assertEquals(0, limiter.retryAfterSeconds("key"));
        assertTrue(limiter.tryAcquire("key"));
    }
}
