package com.autism.seedcracker.motion.pure;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureMemoryTest {

    @Test
    void strikesStackUpToTheCap() {
        FailureMemory m = new FailureMemory(100, 8);
        assertEquals(0, m.penalty(1, 64, 1, 0));
        m.record(1, 64, 1, 0);
        assertEquals(FailureMemory.STRIKE_COST, m.penalty(1, 64, 1, 1));
        for (int i = 0; i < 10; i++) m.record(1, 64, 1, 2);
        assertEquals(FailureMemory.STRIKE_COST * FailureMemory.MAX_STRIKES, m.penalty(1, 64, 1, 3));
        assertEquals(0, m.penalty(1, 65, 1, 3));
    }

    @Test
    void strikesExpireAndRestartFromOne() {
        FailureMemory m = new FailureMemory(100, 8);
        m.record(-5, 10, 7, 0);
        m.record(-5, 10, 7, 0);
        assertEquals(0, m.penalty(-5, 10, 7, 101));
        m.record(-5, 10, 7, 200);
        assertEquals(FailureMemory.STRIKE_COST, m.penalty(-5, 10, 7, 200));
    }

    @Test
    void capacityEvictsTheLeastRecentlyStruck() {
        FailureMemory m = new FailureMemory(1000, 2);
        m.record(0, 0, 0, 0);
        m.record(1, 0, 0, 1);
        m.record(0, 0, 0, 2);
        m.record(2, 0, 0, 3);
        assertEquals(2, m.size());
        assertTrue(m.penalty(0, 0, 0, 4) > 0);
        assertEquals(0, m.penalty(1, 0, 0, 4));
        assertTrue(m.penalty(2, 0, 0, 4) > 0);
    }

    @Test
    void forEachActiveSkipsExpiredAndRoundTripsCoordinates() {
        FailureMemory m = new FailureMemory(50, 8);
        m.record(-30000, -60, 29999, 0);
        m.record(3, 70, -3, 100);
        List<int[]> seen = new ArrayList<>();
        m.forEachActive(120, (x, y, z, c) -> seen.add(new int[]{x, y, z}));
        assertEquals(1, seen.size());
        assertArrayEquals(new int[]{3, 70, -3}, seen.get(0));
    }

    @Test
    void penaltyMakesThePlannerDetourAroundAStruckCell() {
        // 3-wide flat corridor along +x; striking the middle lane's cells pushes the route sideways.
        FailureMemory m = new FailureMemory(1000, 64);
        for (int x = 2; x <= 6; x++) m.record(x, 1, 1, 0);
        GridPathfinder.World w = new GridPathfinder.World() {
            public GridPathfinder.Kind kind(int x, int y, int z) {
                if (y == 0) return GridPathfinder.Kind.SOLID;
                if (z < 0 || z > 2 || x < 0 || x > 8) return GridPathfinder.Kind.SOLID;
                return GridPathfinder.Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return Double.POSITIVE_INFINITY;
            }

            public double avoidCost(int x, int y, int z) {
                return m.penalty(x, y, z, 1);
            }
        };
        var s = GridPathfinder.search(w, GridPathfinder.Config.defaults(), 0, 1, 1, GridPathfinder.block(8, 1, 1));
        s.step(100_000);
        assertEquals(GridPathfinder.Status.FOUND, s.status());
        for (var step : s.path()) {
            assertFalse(step.z() == 1 && step.x() >= 2 && step.x() <= 6, "walked a struck cell at x=" + step.x());
        }
    }
}
