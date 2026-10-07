package com.autism.seedcracker.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global cross-module action governor. Per-module jitter (Humanizer) hides each module's own
 * cadence, but several automations running at once still stack into superhuman combined APM -
 * the tell paid clients solve with ONE shared budget. All container clicks / interactions route
 * through {@link #tryAction}, which enforces:
 *
 *  - a global actions-per-second cap (default 7, roughly fast-human GUI speed),
 *  - a shared exponential backoff while the server shows displeasure (rubber-bands, "too fast"
 *    chat lines, transaction rejections) - one module getting throttled pauses ALL of them,
 *  - session fatigue: base pacing slowly stretches over hours like a human getting tired.
 */
public final class ActionPacer {
    private ActionPacer() {}

    /** Rolling 1s window of granted actions. */
    private static final AtomicInteger windowCount = new AtomicInteger();
    private static volatile long windowStartMs = 0;
    /** All automation pauses until this time (server pushback / backoff). */
    private static volatile long holdUntilMs = 0;
    /** Consecutive pushback events; decays on calm periods. */
    private static final AtomicInteger backoffLevel = new AtomicInteger();
    private static volatile long lastPushbackMs = 0;
    private static volatile long sessionStartMs = System.currentTimeMillis();

    private static final int MAX_ACTIONS_PER_SEC = 7;
    private static final long BACKOFF_DECAY_MS = 60_000;

    /** Call once on world join/leave so fatigue restarts per session. */
    public static void resetSession() {
        sessionStartMs = System.currentTimeMillis();
        backoffLevel.set(0);
        holdUntilMs = 0;
        windowCount.set(0);
        AntiCheatProfile.reset();
    }

    /**
     * Request permission for one automation action (container click, interact, command).
     * Returns true and books the action, or false = wait a tick and ask again.
     */
    public static boolean tryAction() {
        long now = System.currentTimeMillis();
        if (now < holdUntilMs) return false;
        // Decay backoff after a calm minute.
        if (backoffLevel.get() > 0 && now - lastPushbackMs > BACKOFF_DECAY_MS) {
            backoffLevel.decrementAndGet();
            lastPushbackMs = now;
        }
        if (now - windowStartMs >= 1000) {
            windowStartMs = now;
            windowCount.set(0);
        }
        // The cap tightens automatically when the AC guesser identifies a strict anti-cheat.
        int cap = Math.min(MAX_ACTIONS_PER_SEC, AntiCheatProfile.clicksPerSecondCap());
        if (windowCount.get() >= cap) return false;
        windowCount.incrementAndGet();
        return true;
    }

    /**
     * Server pushback observed (rubber-band, throttle chat line, rejected transaction):
     * exponential global hold - 2s, 4s, 8s... capped at 60s - with jitter so the recovery
     * moment itself isn't metronomic.
     */
    public static void onServerPushback(String reason) {
        long now = System.currentTimeMillis();
        lastPushbackMs = now;
        int level = Math.min(com.autism.seedcracker.util.pure.Pacing.MAX_BACKOFF_LEVEL, backoffLevel.incrementAndGet());
        long hold = com.autism.seedcracker.util.pure.Pacing.backoffHoldMs(level, ThreadLocalRandom.current().nextDouble());
        holdUntilMs = Math.max(holdUntilMs, now + hold);
        FlagDetectorModuleBridge.report("pacer", reason, "global hold " + hold + "ms (level " + level + ")");
    }

    /** True while the global hold is active (modules can show "paused" in info()). */
    public static boolean holding() {
        return System.currentTimeMillis() < holdUntilMs;
    }

    /**
     * Fatigue multiplier for base delays: 1.0 for the first 30 min, then +10% per hour up to
     * 1.5x after 5h - long sessions drift slower exactly like a human does.
     */
    public static double fatigueScale() {
        double hours = (System.currentTimeMillis() - sessionStartMs) / 3_600_000.0;
        return com.autism.seedcracker.util.pure.Pacing.fatigueScale(hours);
    }

    /** Reflection-free indirection so the util package never hard-links the modules package. */
    static final class FlagDetectorModuleBridge {
        private static volatile java.util.function.BiConsumer<String, String> sink;

        static void report(String category, String module, String detail) {
            java.util.function.BiConsumer<String, String> s = sink;
            if (s != null) s.accept(category + ":" + module, detail);
        }

        public static void install(java.util.function.BiConsumer<String, String> reporter) {
            sink = reporter;
        }
    }

    /** Install the FlagDetector reporter (called once from the addon entrypoint). */
    public static void installReporter(java.util.function.BiConsumer<String, String> reporter) {
        FlagDetectorModuleBridge.install(reporter);
    }
}
