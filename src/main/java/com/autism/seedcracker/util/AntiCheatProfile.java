package com.autism.seedcracker.util;

/**
 * Behavior profile derived from the AntiCheatGuesser's fingerprint.
 *
 * Identifying the server's anti-cheat is only useful if something consumes the guess: this class
 * turns it into pacing multipliers that Humanizer and ActionPacer apply globally, so all
 * automation automatically slows down on strict servers and runs at full speed on lax ones.
 */
public final class AntiCheatProfile {
    private AntiCheatProfile() {}

    public enum Strictness {
        /** Grim: strictest rotation/timing simulation in the wild. */
        STRICT(1.4, 5),
        /** Vulcan: aggressive heuristics, generous on timings. */
        HIGH(1.2, 6),
        /** Matrix / Verus-likes: mostly movement-focused. */
        MODERATE(1.1, 7),
        /** Unknown/none detected: baseline. */
        BASELINE(1.0, 7);

        public final double delayScale;
        public final int clicksPerSecondCap;

        Strictness(double delayScale, int clicksPerSecondCap) {
            this.delayScale = delayScale;
            this.clicksPerSecondCap = clicksPerSecondCap;
        }
    }

    private static volatile Strictness current = Strictness.BASELINE;
    private static volatile String guessName = "unknown";

    /** Published by AntiCheatGuesserModule when its classification changes. */
    public static void set(String guess) {
        guessName = guess == null ? "unknown" : guess;
        String g = guessName.toLowerCase(java.util.Locale.ROOT);
        if (g.contains("grim")) current = Strictness.STRICT;
        else if (g.contains("vulcan")) current = Strictness.HIGH;
        else if (g.contains("matrix") || g.contains("verus") || g.contains("aac")) current = Strictness.MODERATE;
        else current = Strictness.BASELINE;
    }

    /** Reset to baseline (world leave - the next server may run a different AC). */
    public static void reset() {
        current = Strictness.BASELINE;
        guessName = "unknown";
    }

    /** Multiplier applied to all humanized delays (1.0 = baseline). */
    public static double delayScale() {
        return current.delayScale;
    }

    /** Global actions-per-second cap for ActionPacer under the current profile. */
    public static int clicksPerSecondCap() {
        return current.clicksPerSecondCap;
    }

    public static String guess() {
        return guessName;
    }
}
