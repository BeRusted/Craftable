package org.berusted.craftable.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundAdmissionTest {
    @Test void fixedPacketOrderCannotStarveSecondPlayer() {
        var turns = new CraftableRequestLimiter.BackgroundTurns();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        int a = 0, b = 0;
        for (long tick = 0; tick < 400; tick += 4) {
            boolean firstAccepted = turns.acquire(first, tick, true);
            if (firstAccepted) a++;
            // Worst case: the first page consumes the whole background slice.
            if (turns.acquire(second, tick, !firstAccepted)) b++;
        }
        assertEquals(50, a);
        assertEquals(50, b);
    }

    @Test void departedOrInactivePlayerCannotRetainTurnIndefinitely() {
        var turns = new CraftableRequestLimiter.BackgroundTurns();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        assertTrue(turns.acquire(first, 0, true));
        assertFalse(turns.acquire(second, 0, false));
        assertFalse(turns.acquire(first, 4, true));
        assertTrue(turns.acquire(first, 12, true));
        assertFalse(turns.acquire(second, 12, false));
        turns.remove(second);
        assertTrue(turns.acquire(first, 16, true));
    }
}
