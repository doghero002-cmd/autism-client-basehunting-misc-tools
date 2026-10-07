package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FusionTest {

    @Test
    void singleSignalPassesThrough() {
        assertEquals(50, Fusion.fuse(new int[]{50}));
        assertEquals(0, Fusion.fuse(new int[]{0}));
        assertEquals(100, Fusion.fuse(new int[]{100}));
    }

    @Test
    void twoWeakSignalsReinforce() {
        // noisy-OR: 1 - 0.6*0.6 = 0.64
        assertEquals(64, Fusion.fuse(new int[]{40, 40}));
    }

    @Test
    void agreementSaturatesTowardCertainty() {
        int three = Fusion.fuse(new int[]{70, 70, 70});
        assertTrue(three > 95, "three strong signals should push above 95, got " + three);
        assertTrue(three <= 100);
    }

    @Test
    void zeroAddsNothing() {
        assertEquals(Fusion.fuse(new int[]{55}), Fusion.fuse(new int[]{55, 0}));
    }

    @Test
    void outOfRangeClamped() {
        assertEquals(100, Fusion.fuse(new int[]{150}));
        assertEquals(0, Fusion.fuse(new int[]{-20}));
    }

    @Test
    void emptyIsZero() {
        assertEquals(0, Fusion.fuse(new int[]{}));
    }
}
