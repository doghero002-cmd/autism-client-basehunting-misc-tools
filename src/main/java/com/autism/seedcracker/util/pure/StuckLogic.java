package com.autism.seedcracker.util.pure;

/**
 * Stuck-detection counter (pure - unit tested). See StuckDetector for the live wrapper.
 */
public final class StuckLogic {

    private StuckLogic() {}

    /**
     * Update the no-progress tick counter: reset to 0 when the position moved more than
     * {@code epsilon} since the last sample, else increment. {@code hasLast} is false on the
     * first sample (no movement judgement possible yet).
     */
    public static int update(int stillTicks, boolean hasLast, double movedDistance, double epsilon) {
        if (!hasLast) return stillTicks;
        return movedDistance > epsilon ? 0 : stillTicks + 1;
    }

    /** True when the counter has crossed the stuck threshold. */
    public static boolean isStuck(int stillTicks, int maxTicks) {
        return stillTicks >= maxTicks;
    }
}
