package com.autism.seedcracker.motion.pure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.GridPathfinder.Kind;
import com.autism.seedcracker.motion.pure.GridPathfinder.Move;
import com.autism.seedcracker.motion.pure.GridPathfinder.Status;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomised safety properties over many generated worlds: whatever route comes out, no step
 * stands in or on danger, no non-breaking plan walks through solid blocks, every hop is a legal
 * distance, and drops never exceed what the move allows.
 */
class GridPathfinderPropertyTest {

    private static final class RandomWorld implements GridPathfinder.World {
        final Map<Long, Kind> cells = new HashMap<>();

        RandomWorld(long seed) {
            Random r = new Random(seed);
            for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
                int top = 60 + r.nextInt(5);
                for (int y = 50; y < top; y++) cells.put(GridPathfinder.pack(x, y, z), Kind.SOLID);
                int roll = r.nextInt(100);
                if (roll < 4) cells.put(GridPathfinder.pack(x, top - 1, z), Kind.DANGER);
                else if (roll < 7) cells.put(GridPathfinder.pack(x, top, z), Kind.WATER);
                else if (roll < 9) for (int y = top; y < top + 3; y++) cells.put(GridPathfinder.pack(x, y, z), Kind.SOLID);
                else if (roll < 10) cells.put(GridPathfinder.pack(x, top, z), Kind.LOW);
            }
        }

        @Override
        public Kind kind(int x, int y, int z) {
            if (Math.abs(x) > 24 || Math.abs(z) > 24) return Kind.UNLOADED;
            if (y < 50) return Kind.SOLID;
            return cells.getOrDefault(GridPathfinder.pack(x, y, z), Kind.OPEN);
        }

        @Override
        public double breakCost(int x, int y, int z) {
            return 2.5;
        }
    }

    @Test
    void routesInRandomWorldsAreAlwaysSafeAndLegal() {
        int planned = 0;
        for (long seed = 1; seed <= 60; seed++) {
            RandomWorld w = new RandomWorld(seed);
            int[] s = groundAt(w, -20, -20), g = groundAt(w, 20, 20);
            if (s == null || g == null) continue;
            GridPathfinder.Search search = GridPathfinder.search(w, GridPathfinder.Config.defaults().withParkour(true),
                s[0], s[1], s[2], GridPathfinder.xz(g[0], g[2]));
            while (search.step(2000) == Status.RUNNING) { }
            List<Step> path = search.path();
            if (path.isEmpty()) continue;
            planned++;
            for (int i = 0; i < path.size(); i++) {
                Step st = path.get(i);
                String at = "seed " + seed + " step " + i + " " + st;
                assertNotEquals(Kind.DANGER, w.kind(st.x(), st.y(), st.z()), "stands in danger: " + at);
                assertNotEquals(Kind.DANGER, w.kind(st.x(), st.y() + 1, st.z()), "head in danger: " + at);
                if (st.move() != Move.WATER_DROP && w.kind(st.x(), st.y(), st.z()) != Kind.WATER) {
                    assertNotEquals(Kind.DANGER, w.kind(st.x(), st.y() - 1, st.z()), "stands on danger: " + at);
                }
                assertNotEquals(Kind.SOLID, w.kind(st.x(), st.y(), st.z()), "feet inside a block with breaking off: " + at);
                assertNotEquals(Kind.SOLID, w.kind(st.x(), st.y() + 1, st.z()), "head inside a block with breaking off: " + at);
                if (i == 0) continue;
                Step prev = path.get(i - 1);
                int dx = Math.abs(st.x() - prev.x()), dz = Math.abs(st.z() - prev.z()), dy = st.y() - prev.y();
                if (st.move() == Move.PARKOUR) assertTrue(dx + dz >= 2 && dx + dz <= 4 && (dx == 0 || dz == 0), "bad jump: " + at);
                else assertTrue(dx <= 1 && dz <= 1, "teleporting hop: " + at);
                assertTrue(dy <= 1, "rose more than a block in one hop: " + at);
                if (st.move() != Move.WATER_DROP) assertTrue(-dy <= 3, "drop past maxDrop without water: " + at);
            }
        }
        assertTrue(planned >= 20, "enough random worlds produced a route to be meaningful (" + planned + ")");
    }

    @Test
    void breakingAndPlacingPlansStayWithinTheirRules() {
        int planned = 0;
        for (long seed = 100; seed <= 140; seed++) {
            RandomWorld w = new RandomWorld(seed);
            int[] s = groundAt(w, -18, 0), g = groundAt(w, 18, 0);
            if (s == null || g == null) continue;
            int budget = 6;
            GridPathfinder.Search search = GridPathfinder.search(w,
                GridPathfinder.Config.defaults().withBreaking(true).withPlaceBudget(budget), s[0], s[1], s[2], GridPathfinder.xz(g[0], g[2]));
            while (search.step(2000) == Status.RUNNING) { }
            List<Step> path = search.path();
            if (path.isEmpty()) continue;
            planned++;
            int places = 0;
            for (int i = 1; i < path.size(); i++) {
                Step st = path.get(i);
                String at = "seed " + seed + " step " + i + " " + st;
                if (st.place() != GridPathfinder.NO_PLACE) places++;
                long[] b = st.breaks();
                // Upper blocks first, so nothing above can drop into the gap being cleared.
                for (int k = 1; k < b.length; k++) {
                    assertTrue(GridPathfinder.unpackY(b[k - 1]) >= GridPathfinder.unpackY(b[k]), "break order: " + at);
                }
                for (long p : b) {
                    assertNotEquals(Kind.DANGER, w.kind(GridPathfinder.unpackX(p), GridPathfinder.unpackY(p), GridPathfinder.unpackZ(p)),
                        "breaks a hazard: " + at);
                }
                if (st.place() != GridPathfinder.NO_PLACE) {
                    Kind there = w.kind(GridPathfinder.unpackX(st.place()), GridPathfinder.unpackY(st.place()), GridPathfinder.unpackZ(st.place()));
                    assertTrue(there == Kind.OPEN || there == Kind.LOW, "places into an occupied cell: " + at);
                }
            }
            assertTrue(places <= budget, "spent " + places + " blocks with a budget of " + budget + " (seed " + seed + ")");
        }
        assertTrue(planned >= 15, "enough breaking routes to be meaningful (" + planned + ")");
    }

    private static int[] groundAt(RandomWorld w, int x, int z) {
        for (int y = 70; y > 50; y--) {
            Kind k = w.kind(x, y - 1, z);
            if ((k == Kind.SOLID) && w.kind(x, y, z) == Kind.OPEN && w.kind(x, y + 1, z) == Kind.OPEN) return new int[]{x, y, z};
        }
        return null;
    }
}
