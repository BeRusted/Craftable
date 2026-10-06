package org.berusted.craftable.execution;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftingServiceBudgetTest {
    @Test void scanAndValidationSpendTheSameAdmittedAllowance() {
        var clock = new AtomicLong();
        // One millisecond was left in the server frame at entry. Fresh scanning
        // spends 0.8 ms before the service constructs its search deadline.
        clock.addAndGet(800_000L);
        var budget = CraftingService.planningBudget(() -> 1_000_000L - clock.get(), clock::get);
        assertNotNull(budget);
        assertEquals(200_000L, budget.remainingNanos());
        clock.addAndGet(150_000L);
        assertTrue(budget.alive());
        // Validation gets the remaining 0.05 ms, not another 8 ms deadline.
        clock.addAndGet(50_000L);
        assertFalse(budget.alive());
        assertTrue(budget.truncated());
    }

    @Test void exhaustedAdmissionCannotStartAnotherSearch() {
        assertNull(CraftingService.planningBudget(() -> 0, () -> 0));
        assertNull(CraftingService.planningBudget(() -> -1, () -> 0));
    }

    @Test void directServiceDefaultsAndLargerSuppliersKeepTheEightMillisecondCap() {
        assertEquals(8_000_000L, CraftingService.planningBudget(null, () -> 0).remainingNanos());
        assertEquals(8_000_000L, CraftingService.planningBudget(() -> 16_000_000L, () -> 0).remainingNanos());
    }
}
