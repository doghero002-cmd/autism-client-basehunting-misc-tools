package com.autism.seedcracker.motion.pure;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotionAnomaliesTest {

    @Test
    void smoothWalkingRaisesNothing() {
        MotionAnomalies a = new MotionAnomalies();
        List<String> all = new ArrayList<>();
        for (int t = 0; t < 200; t++) all.addAll(a.sample(t * 0.5f, t * 0.2, 0, 1));
        assertTrue(all.isEmpty(), all.toString());
    }

    @Test
    void detectsAFlick() {
        MotionAnomalies a = new MotionAnomalies();
        a.sample(0, 0, 0, 1);
        List<String> r = a.sample(60, 0.2, 0, 1);
        assertEquals(1, r.size());
        assertTrue(r.get(0).startsWith("FLICK"));
    }

    @Test
    void flickAcrossTheWrapIsMeasuredTheShortWay() {
        MotionAnomalies a = new MotionAnomalies();
        a.sample(179, 0, 0, 1);
        assertTrue(a.sample(-179, 0.2, 0, 1).isEmpty(), "2 degrees across +-180 is not a flick");
    }

    @Test
    void detectsSpinningInPlaceOnce() {
        MotionAnomalies a = new MotionAnomalies();
        int spins = 0;
        for (int t = 0; t < 80; t++) {
            for (String s : a.sample(t * 15f, 0.01 * t, 0, 1)) if (s.startsWith("SPIN")) spins++;
        }
        assertTrue(spins >= 1, "15 deg/tick for 2s without moving is a spin");
        assertTrue(spins <= 2, "reported once per window, not every tick (" + spins + ")");
    }

    @Test
    void turningWhileWalkingAFarCircleIsNotASpin() {
        MotionAnomalies a = new MotionAnomalies();
        List<String> all = new ArrayList<>();
        for (int t = 0; t < 80; t++) {
            double ang = Math.toRadians(t * 9);
            all.addAll(a.sample(t * 9f, Math.cos(ang) * 8, Math.sin(ang) * 8, 1));
        }
        assertTrue(all.stream().noneMatch(s -> s.startsWith("SPIN")), all.toString());
    }

    @Test
    void detectsTwitchyKeys() {
        MotionAnomalies a = new MotionAnomalies();
        boolean seen = false;
        for (int t = 0; t < 60; t++) {
            for (String s : a.sample(0, t * 0.1, 0, t % 2 == 0 ? 0b0101 : 0b1001)) if (s.startsWith("TWITCHY")) seen = true;
        }
        assertTrue(seen);
    }
}
