package org.berusted.craftable.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CraftableRequestLimiterTest {
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
