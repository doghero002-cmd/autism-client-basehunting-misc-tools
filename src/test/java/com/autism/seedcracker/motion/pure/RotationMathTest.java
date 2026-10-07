package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RotationMathTest {

    @Test
    void wrapStaysInRange() {
        assertEquals(-170f, RotationMath.wrap(190f), 1e-4);
        assertEquals(170f, RotationMath.wrap(-190f), 1e-4);
        assertEquals(0f, RotationMath.wrap(720f), 1e-4);
    }

    @Test
    void lookAtMatchesMinecraftConvention() {
        assertEquals(0f, RotationMath.lookAt(0, 0, 1)[0], 1e-3, "south = yaw 0");
        assertEquals(90f, RotationMath.lookAt(-1, 0, 0)[0], 1e-3, "west = yaw 90");
        assertEquals(-90f, RotationMath.lookAt(1, 0, 0)[0], 1e-3, "east = yaw -90");
        assertEquals(-45f, RotationMath.lookAt(0, 1, 1)[1], 1e-3, "looking up is negative pitch");
    }

    @Test
    void convergesWithoutOvershootOrSuperhumanSpeed() {
        RotationMath.Profile p = RotationMath.Profile.defaults();
        RotationMath.State s = new RotationMath.State();
        float yaw = 0, pitch = 0;
        float targetYaw = 150, targetPitch = 30;
        float prevDist = Float.MAX_VALUE;
        int ticks = 0;
        while (ticks++ < 200) {
            RotationMath.Step st = RotationMath.step(yaw, pitch, targetYaw, targetPitch, p, s, 0.5, 0.5);
            float moved = RotationMath.distance(yaw, pitch, st.yaw(), st.pitch());
            assertTrue(moved <= p.maxSpeed() * (1 + p.jitter()) + 1e-3, "per-tick speed capped");
            yaw = st.yaw();
            pitch = st.pitch();
            float d = RotationMath.distance(yaw, pitch, targetYaw, targetPitch);
            assertTrue(d <= prevDist + 1e-3, "never moves away from the target");
            prevDist = d;
            if (st.converged()) break;
        }
        assertTrue(ticks < 40, "a 150 degree turn finishes in under 2s");
        assertEquals(targetYaw, yaw, 1e-3);
        assertEquals(targetPitch, pitch, 1e-3);
    }

    @Test
    void takesShortWayAroundTheWrap() {
        RotationMath.State s = new RotationMath.State();
        RotationMath.Step st = RotationMath.step(170, 0, -170, 0, RotationMath.Profile.defaults(), s, 0.5, 0.5);
        assertTrue(st.yaw() > 170, "turns through 180, not back through 0");
    }

    @Test
    void acceleratesFromRest() {
        RotationMath.Profile p = RotationMath.Profile.defaults();
        RotationMath.State s = new RotationMath.State();
        RotationMath.Step first = RotationMath.step(0, 0, 120, 0, p, s, 0.5, 0.5);
        RotationMath.Step second = RotationMath.step(first.yaw(), 0, 120, 0, p, s, 0.5, 0.5);
        assertTrue(second.yaw() - first.yaw() > first.yaw(), "second tick is faster than the first");
    }

    @Test
    void pitchIsClamped() {
        RotationMath.Step st = RotationMath.step(0, 85, 0, 200, RotationMath.Profile.defaults(), new RotationMath.State(), 0.5, 0.5);
        assertTrue(st.pitch() <= 90f);
    }
}
