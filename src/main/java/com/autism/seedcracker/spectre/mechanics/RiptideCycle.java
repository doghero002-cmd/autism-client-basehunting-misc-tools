package com.autism.seedcracker.spectre.mechanics;

/** Spectre's nine-tick manual release cadence; vanilla remains the sole owner of starting use. */
public final class RiptideCycle {
    private long lastReleased = Long.MIN_VALUE;

    public boolean canRelease(long tick, int chargeTicks, int requiredCharge, boolean manualHeld,
            boolean usingRiptide, boolean acceptedEnvironment) {
        if (!manualHeld || !usingRiptide || !acceptedEnvironment
            || chargeTicks < TridentChargePolicy.repeatMinimum(requiredCharge)) return false;
        return lastReleased == Long.MIN_VALUE || (tick >= lastReleased && tick - lastReleased >= 9);
    }

    public void released(long tick) { lastReleased = tick; }
    public void reset() { lastReleased = Long.MIN_VALUE; }
}
