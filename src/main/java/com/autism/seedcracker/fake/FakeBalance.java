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

    /**
     * Formats a number in DonutSMP shorthand (k / M / B), e.g. 166.3k, 1.8M, 30.
     * Drops the suffix for values under 1000 and trims trailing zeros.
     */
    public static String formatShort(long value) {
        java.util.Locale us = java.util.Locale.US;
        if (Math.abs(value) >= 1_000_000_000L) return trimZeros(String.format(us, "%.1f", value / 1_000_000_000.0)) + "B";
        if (Math.abs(value) >= 1_000_000L) return trimZeros(String.format(us, "%.1f", value / 1_000_000.0)) + "M";
        if (Math.abs(value) >= 1_000L) return trimZeros(String.format(us, "%.1f", value / 1_000.0)) + "k";
        return Long.toString(value);
    }

    private static String trimZeros(String s) {
        if (s.contains(".")) {
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
