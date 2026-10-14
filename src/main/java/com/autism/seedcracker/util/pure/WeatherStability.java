package com.autism.seedcracker.util.pure;

public final class WeatherStability {
    private Boolean stable;
    private int changedTicks;

    public boolean update(boolean observed) {
        if (stable == null) { stable = observed; return false; }
        if (stable == observed) { changedTicks = 0; return false; }
        if (++changedTicks < 20) return false;
        stable = observed;
        changedTicks = 0;
        return true;
    }

    public boolean value() { return Boolean.TRUE.equals(stable); }
    public void reset() { stable = null; changedTicks = 0; }
}