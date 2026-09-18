package org.berusted.craftable.planner;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchBudgetTest {
    @Test void slicePauseIsNotExhaustionAndIdleDoesNotRefillOrSpendCpu() {
        var clock = new AtomicLong();
        var budget = SearchBudget.resumable(clock::get, 100, 10);
        budget.beginSlice(20);
        assertTrue(budget.enter());
        clock.set(20);
        assertFalse(budget.canContinue());
        assertTrue(budget.alive()); // Finish the current atomic task safely.
        assertFalse(budget.truncated());
        budget.endSlice();
        assertEquals(80, budget.remainingNanos());
        clock.set(1_000_000);
        budget.beginSlice(100);
        clock.addAndGet(80);
        assertFalse(budget.alive());
        assertTrue(budget.truncated());
        budget.endSlice();
        assertEquals(0, budget.remainingNanos());
        budget.beginSlice(100);
        assertFalse(budget.canContinue());
        budget.endSlice();
        assertEquals(1, budget.states());
    }

    @Test void sliceBoundariesNeverReplenishStateLimit() {
        var budget = SearchBudget.resumable(() -> 0, 100, 2);
        for (int i = 0; i < 2; i++) {
            budget.beginSlice(10);
            assertTrue(budget.enter());
            budget.endSlice();
        }
        budget.beginSlice(10);
        assertFalse(budget.enter());
        budget.endSlice();
        assertTrue(budget.truncated());
        assertEquals(2, budget.states());
    }

    @Test void stateLimitIsNotAResourceDeficit() {
        var budget = new SearchBudget(() -> 0, 100, 2);
        assertTrue(budget.enter());
        assertTrue(budget.enter());
        assertFalse(budget.enter());
        assertTrue(budget.truncated());
    }

    @Test void deadlineTerminatesEvenWithStatesRemaining() {
        var clock = new AtomicLong();
        var budget = new SearchBudget(clock::get, 100, 10);
        assertTrue(budget.enter());
        clock.set(100);
        assertFalse(budget.enter());
        assertTrue(budget.truncated());
    }

    @Test void candidateTruncationRemainsVisibleWhenClockStillHasTime() {
        var budget = new SearchBudget(() -> 0, 100, 10);
        budget.truncate();
        assertTrue(budget.alive());
        assertTrue(budget.truncated());
    }

    @Test void preferenceCanOnlyRestrictWorldPolicy() {
        for (var world : CraftRequest.PartialPolicy.values()) {
            for (var client : CraftRequest.PartialPolicy.values()) {
                assertTrue(world.restrict(client).ordinal() <= world.ordinal());
                assertTrue(world.restrict(client).ordinal() <= client.ordinal());
            }
        }
    }
}
