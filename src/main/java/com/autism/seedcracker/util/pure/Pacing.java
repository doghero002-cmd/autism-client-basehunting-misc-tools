package com.autism.seedcracker.util.pure;

/**
 * Pacing math behind ActionPacer (pure, unit tested).
 *
 * Two formulas every automation module depends on:
 *  - {@link #backoffHoldMs}: exponential server-pushback hold (2s, 4s, 8s... capped at 60s)
 *  - {@link #fatigueScale}: session-fatigue delay multiplier (1.0 -> 1.5x over hours)
 */
public final class Pacing {
    private Pacing() {}

    /** Hold cap: no single pushback freezes automation longer than this. */
    public static final long MAX_HOLD_MS = 60_000;
    /** Backoff level cap (2^4 * 2s = 32s base, jitter can reach the 60s cap). */
    public static final int MAX_BACKOFF_LEVEL = 5;

    /**
     * Exponential hold for the given backoff level (1-based), with caller-supplied jitter in
     * [0,1): level 1 = 2s, 2 = 4s, 3 = 8s... plus up to +50% jitter, capped at {@link #MAX_HOLD_MS}.
     * Jitter matters: a fixed recovery moment after a throttle is itself a bot signature.
     */
    public static long backoffHoldMs(int level, double jitter01) {
        int l = Math.max(1, Math.min(MAX_BACKOFF_LEVEL, level));
        long base = 2000L << (l - 1);
        long jitterMs = (long) (base * 0.5 * Math.max(0, Math.min(1, jitter01)));
        return Math.min(MAX_HOLD_MS, base + jitterMs);
    }

    /**
     * Fatigue multiplier for base delays given hours since session start:
     * 1.0 for the first 30 minutes, then +10% per hour, capped at 1.5x (reached after ~5.5h).
     * Mirrors how a human's pace drifts over a long session.
     */
    public static double fatigueScale(double hoursSinceStart) {
        if (hoursSinceStart <= 0.5) return 1.0;
        return Math.min(1.5, 1.0 + (hoursSinceStart - 0.5) * 0.1);
    }
}
