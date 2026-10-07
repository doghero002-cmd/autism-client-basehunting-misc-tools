package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplorePlannerTest {

    @Test
    void startsAtOriginThenRingsOutward() {
        ExplorePlanner p = new ExplorePlanner(0, 0, 4, 0);
        assertArrayEquals(new int[]{0, 0}, p.next(0, 0, 3));
        p.markSeen(0, 0);
        int[] n = p.next(0, 0, 3);
        assertEquals(4, Math.max(Math.abs(n[0]), Math.abs(n[1])), "next target is on ring 1");
    }

    @Test
    void picksTheNearestUnseenOnTheRing() {
        ExplorePlanner p = new ExplorePlanner(0, 0, 4, 0);
        p.markSeen(0, 0);
        assertArrayEquals(new int[]{4, 0}, p.next(5, 0, 3));
    }

    @Test
    void abandonedAndSeenTargetsAreSkippedAndRunEnds() {
        ExplorePlanner p = new ExplorePlanner(0, 0, 4, 0);
        p.markSeen(0, 0);
        for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) if (dx != 0 || dz != 0) p.abandon(dx, dz);
        assertNull(p.next(0, 0, 1), "ring 0 seen, ring 1 abandoned, max ring 1: nothing left");
    }

    @Test
    void viewDistanceCountsSurroundedTargetsAsExplored() {
        ExplorePlanner p = new ExplorePlanner(0, 0, 4, 2);
        p.markSeen(2, 0);
        p.markSeen(-2, 0);
        p.markSeen(0, 2);
        p.markSeen(0, -2);
        int[] n = p.next(0, 0, 2);
        assertNotNull(n);
        assertFalse(n[0] == 0 && n[1] == 0, "origin is covered by loaded chunks around it");
    }
}
