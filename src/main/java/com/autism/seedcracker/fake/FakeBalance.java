package com.autism.seedcracker.fake;

/**
 * Shared fake-balance state used by Fake Pay and Fake Scoreboard. Kept separate so both
 * modules read/write the same number without depending on each other being enabled.
 */
public final class FakeBalance {
    private static volatile long balance = 0L;

    private FakeBalance() {}

    public static long get() {
        return balance;
    }

    public static void set(long value) {
        balance = Math.max(0L, value);
    }

    public static void add(long delta) {
        set(balance + delta);
    }

    /** Formats a number with commas (e.g. 1,234,567). */
    public static String format(long value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }
}
