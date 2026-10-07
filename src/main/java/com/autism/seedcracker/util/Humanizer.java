package com.autism.seedcracker.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared human-pacing helpers for the automation modules. Fixed metronomic delays are the
 * single biggest statistical bot tell in container/click automation - every delay here is
 * jittered and TPS-scaled so cadence varies like a human on a laggy server. Centralised so
 * every module gets the same (tested) behaviour instead of six ad-hoc copies.
 */
public final class Humanizer {
    private Humanizer() {}

    /**
     * Jittered, TPS-scaled tick delay: base +- up to jitterFrac (default callers 0.35), with an
     * occasional longer "distraction" pause (~4% of draws, 2-3x base) like a human glancing away.
     * Always >= 1.
     */
    public static int delay(int baseTicks) {
        return delay(baseTicks, 0.35);
    }

    public static int delay(int baseTicks, double jitterFrac) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double base = Math.max(1, baseTicks) * ActionPacer.fatigueScale() * AntiCheatProfile.delayScale();
        if (r.nextInt(100) < 4) {
            base *= 2.0 + r.nextDouble(); // distraction pause
        } else {
            base *= 1.0 + (r.nextDouble() * 2 - 1) * jitterFrac;
        }
        return Math.max(1, TickRateTracker.scale((int) Math.round(base)));
    }

    /** Jittered millisecond delay for off-thread pacing (API polls etc). */
    public static long delayMs(long baseMs) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double v = baseMs * AntiCheatProfile.delayScale() * (1.0 + (r.nextDouble() * 2 - 1) * 0.3);
        return Math.max(50, (long) v);
    }

    /** True once per ~n draws (cheap "sometimes hesitate" gate). */
    public static boolean oneIn(int n) {
        return ThreadLocalRandom.current().nextInt(Math.max(1, n)) == 0;
    }
}
