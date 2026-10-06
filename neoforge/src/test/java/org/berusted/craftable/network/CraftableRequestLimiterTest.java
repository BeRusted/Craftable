package org.berusted.craftable.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CraftableRequestLimiterTest {
    @Test
    void activeRequestsBorrowOnlyTheSharedTicksRemainingTime() {
        var clock = new AtomicLong();
        var frame = new CraftableRequestLimiter.Frame(clock::get);
        var player = UUID.randomUUID();
        try (var first = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertEquals(8_000_000L, first.remaining());
            clock.addAndGet(8_000_000L);
        }
        try (var second = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            clock.addAndGet(7_000_000L);
        }
        try (var third = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertEquals(1_000_000L, third.remaining());
            // Discovery is part of the admitted request, before solver work.
            clock.addAndGet(800_000L);
            assertEquals(200_000L, third.remaining());
            clock.addAndGet(200_000L);
            assertEquals(0, third.remaining());
        }
        try (var denied = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertFalse(denied.allowed());
        }
        try (var nextTick = CraftableRequestLimiter.planning(frame, 101, player, true)) {
            assertEquals(8_000_000L, nextTick.remaining());
        }
    }

    @Test
    void backgroundExhaustionLeavesTheReservedActiveAllowance() {
        var clock = new AtomicLong();
        var frame = new CraftableRequestLimiter.Frame(clock::get);
        try (var first = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            clock.addAndGet(6_000_000L);
        }
        try (var second = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            assertEquals(2_000_000L, second.remaining());
            clock.addAndGet(2_000_000L);
        }
        try (var background = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            assertFalse(background.allowed());
        }
        try (var active = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), true)) {
            assertEquals(8_000_000L, active.remaining());
        }
    }

    @Test
    void statusRequestsAreLimitedPerPlayer() {
        UUID player = UUID.randomUUID();

        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertFalse(CraftableRequestLimiter.allowStatus(player, 103));
        assertTrue(CraftableRequestLimiter.allowStatus(player, 104));
    }

    @Test
    void aClockResetDoesNotLockAPlayerOut() {
        UUID player = UUID.randomUUID();

        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 5));
    }

    @Test
    void clearingAPlayerReleasesBothLimits() {
        UUID player = UUID.randomUUID();
        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));

        CraftableRequestLimiter.clear(player);

        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));
    }
}
