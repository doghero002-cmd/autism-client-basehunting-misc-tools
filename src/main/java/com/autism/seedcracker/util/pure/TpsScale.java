package com.autism.seedcracker.util.pure;

/**
 * TPS delay scaling math (pure - unit tested). See TickRateTracker for the live wrapper.
 */
public final class TpsScale {

    private TpsScale() {}

    /** Scale a tick delay to real server ticks: 20 TPS unchanged, 10 TPS doubles, floor gated. */
    public static int scale(int ticks, float tps, float scaleFloor) {
        if (tps >= scaleFloor) return ticks;
        return Math.max(1, Math.round(ticks * (20.0f / Math.max(1.0f, tps))));
    }

    /** Millisecond variant of {@link #scale}. */
    public static long scaleMs(long ms, float tps, float scaleFloor) {
        if (tps >= scaleFloor) return ms;
        return Math.round(ms * (20.0 / Math.max(1.0f, tps)));
    }
}
