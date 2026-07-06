package com.kishku7.m1.agent;

/**
 * Adaptive self-budgeting. The mod measures its own per-tick cost and scales its discretionary
 * ambition to fit a target slice -- run heavy work (pathfinding, graph housekeeping, rich
 * digests) when there is headroom, defer it under load.
 *
 * <p>HARD rule of the design: flex the discretionary, NEVER the reflexes. The survival sensors
 * and the current action's tick-step are a protected floor that always runs; only discretionary
 * jobs are gated by {@link #hasHeadroomForDiscretionary}. This keeps "always interruptible" true
 * even when the game is busiest.
 *
 * <p>Skeleton: tracks an exponentially-weighted moving average of measured tick cost against a
 * configurable target. Not thread-safe; driven only from the tick thread.
 */
public final class Budgeter {

    private final double targetNanos;
    private final double alpha;
    private double avgNanos;

    public Budgeter(double targetMillisPerTick) {
        this(targetMillisPerTick, 0.2);
    }

    public Budgeter(double targetMillisPerTick, double alpha) {
        this.targetNanos = targetMillisPerTick * 1_000_000.0;
        this.alpha = alpha;
        this.avgNanos = 0.0;
    }

    /** Fold this tick's measured engine cost (nanoseconds) into the moving average. */
    public void recordTickCost(long nanos) {
        avgNanos = (avgNanos == 0.0) ? nanos : (alpha * nanos + (1.0 - alpha) * avgNanos);
    }

    /**
     * True when recent engine cost leaves room to run discretionary (offloadable) work this tick.
     * Reflex sensing and current-action stepping ignore this and always run (protected floor).
     */
    public boolean hasHeadroomForDiscretionary() {
        return avgNanos < targetNanos;
    }

    /** Current smoothed engine cost estimate, in milliseconds. */
    public double avgMillis() {
        return avgNanos / 1_000_000.0;
    }
}
