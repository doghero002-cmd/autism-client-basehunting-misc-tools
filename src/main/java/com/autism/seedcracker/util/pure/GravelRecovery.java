package com.autism.seedcracker.util.pure;

public final class GravelRecovery {
    public enum Step { IDLE, RETREAT, CLEAR, STOP }

    private final int retreatTicks;
    private final int maxTicks;
    private boolean active;
    private int remaining;
    private int elapsed;

    public GravelRecovery(int retreatTicks, int maxTicks) {
        if (retreatTicks < 0 || maxTicks < 1) throw new IllegalArgumentException("Invalid recovery limits");
        this.retreatTicks = retreatTicks;
        this.maxTicks = maxTicks;
    }

    public Step tick(boolean threat, boolean retreatSafe) {
        if (!threat) { reset(); return Step.IDLE; }
        if (!active) { active = true; remaining = retreatTicks; }
        if (elapsed >= maxTicks) return Step.STOP;
        elapsed++;
        if (remaining > 0) {
            if (!retreatSafe) return Step.STOP;
            remaining--;
            return Step.RETREAT;
        }
        return Step.CLEAR;
    }

    public void reset() {
        active = false;
        remaining = 0;
        elapsed = 0;
    }
}