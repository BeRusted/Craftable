package org.berusted.craftable.planner;

import java.util.function.LongSupplier;

/** Cooperative limits: an individual Minecraft recipe call cannot be preempted. */
public final class SearchBudget {
    public static final int MAX_STEPS = 128;
    public static final int MAX_DEPTH = 12;
    public static final int MAX_STATES = 2048;
    public static final int MAX_CANDIDATES = 16;
    public static final int MAX_FRONTIER = 128;
    public static final int MAX_MAXIMUM_SLICES = 32;
    private final LongSupplier clock;
    private final long deadline;
    private final int maximumStates;
    private int states;
    private boolean truncated;
    private boolean resumable;
    private long remaining;
    private long started;
    private long sliceDeadline;
    private boolean active;

    public SearchBudget(long nanos) {
        this(System::nanoTime, nanos, MAX_STATES);
    }

    public SearchBudget(LongSupplier clock, long nanos, int maximumStates) {
        if (nanos <= 0 || maximumStates <= 0) throw new IllegalArgumentException("Invalid search budget");
        this.clock = clock;
        this.deadline = clock.getAsLong() + nanos;
        this.maximumStates = maximumStates;
    }

    public boolean enter() {
        if (!alive() || ++states > maximumStates) {
            truncated = true;
            return false;
        }
        return true;
    }

    public boolean alive() {
        boolean alive = (resumable ? remaining > (active ? clock.getAsLong() - started : 0)
                : clock.getAsLong() < deadline) && states < maximumStates;
        if (!alive) truncated = true;
        return alive;
    }

    public void truncate() { truncated = true; }
    public boolean truncated() { return truncated; }
    public int states() { return states; }

    /** CPU allowance survives pauses; waiting between ticks spends no CPU and
     * starting a new slice never replenishes the target's total allowance. */
    public static SearchBudget resumable(LongSupplier clock, long nanos, int states) {
        var result = new SearchBudget(clock, nanos, states);
        result.resumable = true;
        result.remaining = nanos;
        return result;
    }

    public static SearchBudget resumable(long nanos) {
        return resumable(System::nanoTime, nanos, MAX_STATES);
    }

    void beginSlice(long nanos) {
        if (active || nanos <= 0) throw new IllegalStateException("Invalid or overlapping search slice");
        started = clock.getAsLong();
        sliceDeadline = started + Math.min(nanos, Long.MAX_VALUE / 2);
        active = true;
    }

    void endSlice() {
        if (active && resumable) remaining = Math.max(0, remaining - Math.max(0, clock.getAsLong() - started));
        active = false;
    }

    // Only check a slice boundary BEFORE removing a task/closure entry. Calls
    // inside one state use alive(), so a pause cannot discard a half-bound grid.
    boolean canContinue() { return alive() && clock.getAsLong() < sliceDeadline; }
    public long remainingNanos() { return resumable ? remaining : Math.max(0, deadline - clock.getAsLong()); }
}
