package com.autism.seedcracker.motion.pure;

public final class ScoutSafety {
    public enum Action { CONTINUE, PAUSE, STOP, EMERGENCY }
    public record Verdict(Action action, String reason) {}
    public record Vitals(double health, double previousHealth, int food, int air, double fall,
            boolean fire, boolean lava, boolean hurt, boolean playerNearby, boolean hostileNearby,
            boolean suppliesReady, boolean menuOpen, boolean lagging, boolean manualInput, boolean unsafeAhead) {}

    private ScoutSafety() {}

    public static GridPathfinder.Config walkingConfig(GridPathfinder.Config configured) {
        return new GridPathfinder.Config(0, false, false, configured.jumpCost(),
            Math.max(1000, configured.hazardPenalty()), Math.min(60_000, configured.maxNodes()),
            false, 0, false, false, false, configured.heuristicWeight(), false);
    }

    public static Verdict check(Vitals state, double minimumHealth) {
        if (!Double.isFinite(state.health) || !Double.isFinite(state.fall) || !Double.isFinite(minimumHealth)) return new Verdict(Action.STOP, "invalid vitals");
        if (state.health <= 0) return new Verdict(Action.STOP, "already dead");
        if (state.fire || state.lava) return new Verdict(Action.EMERGENCY, "fire or lava");
        if (state.hurt || state.previousHealth >= 0 && state.health + 0.001 < state.previousHealth) return new Verdict(Action.EMERGENCY, "damage received");
        if (state.health <= minimumHealth) return new Verdict(Action.EMERGENCY, "health reserve reached");
        if (state.air < 240) return new Verdict(Action.EMERGENCY, "air reserve reached");
        if (state.fall >= 2.5) return new Verdict(Action.EMERGENCY, "unsafe fall");
        if (state.playerNearby) return new Verdict(Action.STOP, "another player nearby");
        if (state.hostileNearby) return new Verdict(Action.STOP, "hostile nearby");
        if (state.lagging) return new Verdict(Action.STOP, "connection or tick lag");
        if (!state.suppliesReady || state.food < 16) return new Verdict(Action.STOP, "supplies or hunger reserve");
        if (state.manualInput) return new Verdict(Action.STOP, "manual takeover");
        if (state.unsafeAhead) return new Verdict(Action.STOP, "unsafe or unloaded terrain ahead");
        if (state.menuOpen) return new Verdict(Action.PAUSE, "screen open");
        return new Verdict(Action.CONTINUE, "clear");
    }
}