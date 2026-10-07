package com.autism.seedcracker.motion.pure;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.GridPathfinder.Kind;
import com.autism.seedcracker.motion.pure.GridPathfinder.Status;

import static org.junit.jupiter.api.Assertions.*;

/** Guards planner speed: a noisy 400x400 world must plan across in well under the per-tick budget scale. */
class PathfinderBenchTest {

    /** Rolling terrain with random pillars and pits, fixed seed so runs are comparable. */
    private static final class NoisyWorld implements GridPathfinder.World {
        private final Map<Long, Integer> height = new HashMap<>();
        private final Random r = new Random(42);

        private int h(int x, int z) {
            long k = (long) x << 32 | (z & 0xFFFFFFFFL);
            return height.computeIfAbsent(k, kk -> 64 + (int) Math.round(Math.sin(x * 0.11) * 2 + Math.cos(z * 0.07) * 2)
                + (r.nextInt(40) == 0 ? 3 : 0));
        }

        @Override
        public Kind kind(int x, int y, int z) {
            if (Math.abs(x) > 200 || Math.abs(z) > 200) return Kind.UNLOADED;
            int top = h(x, z);
            if (y < top) return Kind.SOLID;
            if (y == top && (x * 31 + z * 17) % 97 == 0) return Kind.WATER;
            return Kind.OPEN;
        }

        @Override
        public double breakCost(int x, int y, int z) {
            return 3.0;
        }
    }

    /** Prints throughput so planner changes can be compared run to run (see the test report's stdout). */
    @Test
    void reportsThroughput() {
        NoisyWorld w = new NoisyWorld();
        for (int i = 0; i < 3; i++) run(w);
        long t0 = System.nanoTime();
        int nodes = 0;
        for (int i = 0; i < 5; i++) nodes += run(w).expanded();
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("PLANNER_BENCH nodes=%d ms=%.1f nodes_per_ms=%.0f%n", nodes, ms, nodes / ms);
    }

    @Test
    void crossesANoisyWorldQuickly() {
        NoisyWorld w = new NoisyWorld();
        // Warm up the JIT so the timed run measures the planner, not class loading.
        for (int i = 0; i < 3; i++) run(w);
        long t0 = System.nanoTime();
        GridPathfinder.Search s = run(w);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotEquals(Status.FAILED, s.status());
        assertTrue(ms < 1500, "planning a ~280-block route took " + ms + "ms (" + s.expanded() + " nodes)");
    }

    @Test
    void openFlatGroundExpandsACorridorNotTheWholeArea() {
        GridPathfinder.World flat = new GridPathfinder.World() {
            public Kind kind(int x, int y, int z) {
                if (Math.abs(x) > 200 || Math.abs(z) > 200) return Kind.UNLOADED;
                return y <= 63 ? Kind.SOLID : Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return 2;
            }
        };
        GridPathfinder.Search s = GridPathfinder.search(flat, GridPathfinder.Config.defaults(), -150, 64, 0, GridPathfinder.block(150, 64, 0));
        while (s.step(5000) == Status.RUNNING) { }
        assertEquals(Status.FOUND, s.status());
        // A 300-block straight walk should expand a few thousand nodes at most, not tens of thousands.
        assertTrue(s.expanded() < 5000, "expanded " + s.expanded() + " nodes for a straight 300-block walk");
    }

    @Test
    void offThreadBudgetCrossesAMazeTheOldBudgetCouldNot() {
        // Walls with one gap each, alternating sides: the route snakes ~5x the straight distance.
        GridPathfinder.World maze = new GridPathfinder.World() {
            public Kind kind(int x, int y, int z) {
                if (Math.abs(x) > 200 || Math.abs(z) > 200) return Kind.UNLOADED;
                if (y <= 63) return Kind.SOLID;
                if (y > 66) return Kind.OPEN;
                if (x % 8 == 0 && x > -200 && x < 200) {
                    boolean gapLow = (x / 8) % 2 == 0;
                    return (gapLow ? z == -199 : z == 199) ? Kind.OPEN : Kind.WALL;
                }
                return Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return Double.POSITIVE_INFINITY;
            }
        };
        var old = GridPathfinder.search(maze, GridPathfinder.Config.defaults(), -190, 64, 0, GridPathfinder.xz(190, 0));
        while (old.step(5000) == Status.RUNNING) { }
        var big = GridPathfinder.search(maze, GridPathfinder.Config.defaults().withMaxNodes(250_000), -190, 64, 0, GridPathfinder.xz(190, 0));
        AsyncPlan p = AsyncPlan.runNow(big, 20_000);
        assertEquals(Status.FOUND, p.status(), "the full maze fits the off-thread budget");
        assertNotEquals(Status.FOUND, old.status(), "sanity: the old 40k budget gives up partway");
    }

    @Test
    void weightedSearchIsMuchCheaperAndWithinItsCostBound() {
        NoisyWorld w = new NoisyWorld();
        double optimalCost = 0;
        int optimalNodes = 0;
        for (double weight : new double[]{1.0, 1.1, 1.2}) {
            var s = GridPathfinder.search(w, GridPathfinder.Config.defaults().withMaxNodes(2_000_000).withHeuristicWeight(weight),
                -150, 66, -150, GridPathfinder.xz(150, 150));
            while (s.step(5000) == Status.RUNNING) { }
            assertEquals(Status.FOUND, s.status());
            double cost = s.path().stream().mapToDouble(GridPathfinder.Step::cost).sum();
            System.out.printf("WEIGHT %.1f nodes=%d cost=%.1f%n", weight, s.expanded(), cost);
            if (weight == 1.0) {
                optimalCost = cost;
                optimalNodes = s.expanded();
                continue;
            }
            assertTrue(cost <= optimalCost * weight + 1e-6, "w=" + weight + " cost " + cost + " vs optimal " + optimalCost);
            assertTrue(s.expanded() * 2 < optimalNodes, "w=" + weight + " expanded " + s.expanded() + " vs " + optimalNodes);
        }
    }

    @Test
    void farGoalPastTheLoadedEdgeSettlesInsteadOfFloodingTheEdge() {
        // 400x400 loaded, goal 5000 blocks out: the partial at the edge is known early; the rest is sideways flooding.
        GridPathfinder.World flat = new GridPathfinder.World() {
            public Kind kind(int x, int y, int z) {
                if (Math.abs(x) > 200 || Math.abs(z) > 200) return Kind.UNLOADED;
                return y <= 63 ? Kind.SOLID : Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return 2;
            }
        };
        GridPathfinder.Search s = GridPathfinder.search(flat, GridPathfinder.Config.defaults().withMaxNodes(250_000), 0, 64, 0,
            GridPathfinder.xz(5000, 0));
        while (s.step(5000) == Status.RUNNING) { }
        assertEquals(Status.PARTIAL, s.status());
        // Long partials drop their last 10%, so the walked end is ~180 of the 200 to the edge.
        int endX = s.path().get(s.path().size() - 1).x();
        assertTrue(endX >= 170, "still walks out toward the loaded edge (ended at x=" + endX + ")");
        assertTrue(s.expanded() < 100_000, "stopped flooding the edge after " + s.expanded() + " nodes");
    }

    private static GridPathfinder.Search run(NoisyWorld w) {
        GridPathfinder.Search s = GridPathfinder.search(w, GridPathfinder.Config.defaults(), -190, 66, -190,
            GridPathfinder.xz(190, 190));
        while (s.step(5000) == Status.RUNNING) { }
        return s;
    }
}
