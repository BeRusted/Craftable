package org.berusted.craftable.planner;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchBudgetTest {
    @Test void stepLimitsDoNotChangeTimeStateOrBranchAllowances() {
        assertEquals(128, SearchBudget.DEFAULT_MAX_STEPS);
        assertEquals(256, SearchBudget.HARD_MAX_STEPS);
        for (int limit : new int[] {1, 128, 256}) assertEquals(limit, SearchBudget.requireStepLimit(limit));
        for (int limit : new int[] {Integer.MIN_VALUE, 0, 257, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> SearchBudget.requireStepLimit(limit));
        assertEquals(12, SearchBudget.MAX_DEPTH);
        assertEquals(2048, SearchBudget.MAX_STATES);
        assertEquals(16, SearchBudget.MAX_CANDIDATES);
        assertEquals(128, SearchBudget.MAX_FRONTIER);
        assertEquals(32, SearchBudget.MAX_MAXIMUM_SLICES);
        var clock = new AtomicLong();
        var budget = SearchBudget.resumable(clock::get, 100, 2);
        budget.beginSlice(20);
        assertTrue(budget.enter());
        clock.set(20);
        budget.endSlice();
        SearchBudget.requireStepLimit(256);
        assertEquals(80, budget.remainingNanos());
        assertEquals(1, budget.states());
        clock.set(1_000_000);
        budget.beginSlice(100);
        assertTrue(budget.enter());
        assertFalse(budget.enter());
        budget.endSlice();
        assertEquals(2, budget.states());
        assertTrue(budget.exhausted());
    }

    @Test void omittedBranchDoesNotExhaustTheRetainedContinuation() {
        var clock = new AtomicLong();
        var budget = SearchBudget.resumable(clock::get, 100, 10);
        budget.beginSlice(20);
        budget.truncate();
        assertTrue(budget.truncated());
        assertFalse(budget.exhausted());
        assertTrue(budget.enter());
        clock.set(20);
        assertFalse(budget.canContinue());
        assertFalse(budget.exhausted());
        assertEquals(80, budget.remainingNanos());
        budget.endSlice();
        clock.set(1_000_000);
        budget.beginSlice(20);
        assertTrue(budget.enter());
        assertFalse(budget.exhausted());
        budget.endSlice();
    }

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
