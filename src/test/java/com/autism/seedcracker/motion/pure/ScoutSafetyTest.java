package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoutSafetyTest {
    @Test void guardedWalkingOverridesEveryRiskyNavigationOption() {
        var unsafe = new GridPathfinder.Config(20,true,true,1,0,250_000,true,64,true,true,true,1.3,true);
        var safe = ScoutSafety.walkingConfig(unsafe);
        assertEquals(0,safe.maxDrop()); assertFalse(safe.allowBreak()); assertFalse(safe.allowSwim());
        assertFalse(safe.allowParkour()); assertEquals(0,safe.placeBudget()); assertFalse(safe.longParkour());
        assertFalse(safe.diagonalVertical()); assertFalse(safe.waterBucket()); assertFalse(safe.cushionBlocks());
        assertEquals(60_000,safe.maxNodes()); assertTrue(safe.hazardPenalty() >= 1000);
    }

    private static ScoutSafety.Vitals state(double health, double previous, int food, int air, double fall,
            boolean fire, boolean lava, boolean hurt, boolean player, boolean hostile, boolean supplies,
            boolean menu, boolean lag, boolean input, boolean terrain) {
        return new ScoutSafety.Vitals(health, previous, food, air, fall, fire, lava, hurt, player, hostile, supplies, menu, lag, input, terrain);
    }

    @Test void healthyPreparedPlayerMayProceedButMenusPause() {
        assertEquals(ScoutSafety.Action.CONTINUE, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.PAUSE, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,true,true,false,false,false),18).action());
    }

    @Test void damageHealthFireLavaAirAndFallsEmergencyStopEvenWithAMenu() {
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(19,20,20,300,0,false,false,false,false,false,true,true,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(18,18,20,300,0,false,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(20,20,20,300,0,true,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(20,20,20,300,0,false,true,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(20,20,20,239,0,false,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(20,20,20,300,2.5,false,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.EMERGENCY, ScoutSafety.check(state(20,20,20,300,0,false,false,true,false,false,true,false,false,false,false),18).action());
    }

    @Test void threatsSuppliesLagInputAndUnknownTerrainStopWithoutBlindResume() {
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,true,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,true,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,false,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,15,300,0,false,false,false,false,false,true,false,false,false,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,true,false,true,false,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,true,false,false,true,false),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(20,20,20,300,0,false,false,false,false,false,true,false,false,false,true),18).action());
        assertEquals(ScoutSafety.Action.STOP, ScoutSafety.check(state(Double.NaN,20,20,300,0,false,false,false,false,false,true,false,false,false,false),18).action());
    }
}