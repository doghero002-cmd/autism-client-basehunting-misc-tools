package com.autism.seedcracker.util;

/**
 * TPS-scaled delay helpers over the AUTISM client's own ServerTickTracker (which is already fed
 * from world-time packets by the base client - no mixin needed here).
 *
 * Automation that paces itself in client ticks acts too fast relative to a lagging server (break
 * desync, GUI clicks landing early), which both fails and looks robotic (Boze AutoMineTpsSync).
 * scale(int) / scaleMs(long) stretch delays to match real server speed.
 */
public final class TickRateTracker {

    private TickRateTracker() {}

    /** Server TPS from the base client's tracker (20 = healthy; 20 when unknown). */
    public static float averageTps() {
        try {
            double tps = autismclient.util.macro.ServerTickTracker.getEstimatedTps();
            if (tps <= 0 || Double.isNaN(tps)) return 20.0f;
            return (float) Math.min(20.0, tps);
        } catch (Throwable t) {
            return 20.0f;
        }
    }

    /** Scale a tick delay so it matches real server ticks (20 TPS -> unchanged, 10 TPS -> 2x). */
    public static int scale(int ticks) {
        return com.autism.seedcracker.util.pure.TpsScale.scale(ticks, averageTps(), Tuning.TPS_SCALE_FLOOR);
    }

    /** Millisecond variant of scale(int). */
    public static long scaleMs(long ms) {
        return com.autism.seedcracker.util.pure.TpsScale.scaleMs(ms, averageTps(), Tuning.TPS_SCALE_FLOOR);
    }
}
