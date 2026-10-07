package com.autism.seedcracker.motion.pure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.GridPathfinder.Kind;
import com.autism.seedcracker.motion.pure.GridPathfinder.Move;
import com.autism.seedcracker.motion.pure.GridPathfinder.Status;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;

import static org.junit.jupiter.api.Assertions.*;

class GridPathfinderTest {

    /** Flat stone floor at y=63 (feet at y=64), everything else open, 40x40 loaded. */
    private static class TestWorld implements GridPathfinder.World {
        final Map<Long, Kind> overrides = new HashMap<>();

        TestWorld set(int x, int y, int z, Kind k) {
            overrides.put(GridPathfinder.pack(x, y, z), k);
            return this;
        }

        TestWorld wall(int x, int z, int y0, int y1, Kind k) {
            for (int y = y0; y <= y1; y++) set(x, y, z, k);
            return this;
        }

        @Override
        public Kind kind(int x, int y, int z) {
            if (Math.abs(x) > 20 || Math.abs(z) > 20) return Kind.UNLOADED;
            Kind k = overrides.get(GridPathfinder.pack(x, y, z));
            if (k != null) return k;
            return y <= 63 ? Kind.SOLID : Kind.OPEN;
        }

        @Override
        public double breakCost(int x, int y, int z) {
            return y <= 0 ? Double.POSITIVE_INFINITY : 2.0;
        }
    }

    private static List<Step> run(TestWorld w, GridPathfinder.Config c, int sx, int sy, int sz, GridPathfinder.Goal g) {
        GridPathfinder.Search s = GridPathfinder.search(w, c, sx, sy, sz, g);
        while (s.step(500) == Status.RUNNING) { }
        return s.status() == Status.FOUND ? s.path() : List.of();
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long p = GridPathfinder.pack(-30_000_000 + 7, -64, 29_999_999);
        assertEquals(-30_000_000 + 7, GridPathfinder.unpackX(p));
        assertEquals(-64, GridPathfinder.unpackY(p));
        assertEquals(29_999_999, GridPathfinder.unpackZ(p));
    }

    @Test
    void straightLineOnFlatGroundUsesDiagonals() {
        List<Step> path = run(new TestWorld(), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(5, 64, 5));
        assertFalse(path.isEmpty());
        assertEquals(Move.START, path.get(0).move());
        Step last = path.get(path.size() - 1);
        assertEquals(5, last.x());
        assertEquals(5, last.z());
        assertEquals(6, path.size(), "5 diagonal steps + start");
    }

    @Test
    void stepsUpOneBlockButNotTwo() {
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 20; z++) w.set(3, 64, z, Kind.SOLID);
        List<Step> up = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 65, 0));
        assertTrue(up.stream().anyMatch(s -> s.move() == Move.ASCEND));

        TestWorld tall = new TestWorld();
        for (int z = -20; z <= 20; z++) tall.wall(3, z, 64, 65, Kind.SOLID);
        assertTrue(run(tall, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(5, 64, 0)).isEmpty(),
            "a 2-high wall with no gaps and no breaking is impassable");
    }

    @Test
    void breakingOpensATunnelThroughAWall() {
        TestWorld tall = new TestWorld();
        // 3 high: hopping over would need the same 2 breaks plus a jump, so tunnelling is strictly cheaper.
        for (int z = -20; z <= 20; z++) tall.wall(3, z, 64, 66, Kind.SOLID);
        List<Step> path = run(tall, GridPathfinder.Config.defaults().withBreaking(true), 0, 64, 0, GridPathfinder.block(5, 64, 0));
        assertFalse(path.isEmpty());
        Step through = path.stream().filter(s -> s.x() == 3).findFirst().orElseThrow();
        assertEquals(2, through.breaks().length);
        assertEquals(65, GridPathfinder.unpackY(through.breaks()[0]), "head block is broken first");
    }

    @Test
    void avoidsLavaAndPrefersDetour() {
        TestWorld w = new TestWorld();
        for (int z = -2; z <= 2; z++) w.set(3, 63, z, Kind.DANGER);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertFalse(path.isEmpty());
        for (Step s : path) assertFalse(s.x() == 3 && Math.abs(s.z()) <= 2, "never stands over lava");
    }

    @Test
    void dropsAtMostMaxDrop() {
        TestWorld w = new TestWorld();
        // A pit: floor at 59 for x>=3 (feet would land at 60 = a 4-block drop).
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 60; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 60, 0)).isEmpty());
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) w.set(x, 60, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 61, 0));
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.DESCEND));
    }

    @Test
    void noCornerCuttingThroughWalls() {
        TestWorld w = new TestWorld().wall(1, 0, 64, 65, Kind.SOLID).wall(0, 1, 64, 65, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(1, 64, 1));
        assertTrue(path.isEmpty() || path.get(1).move() != Move.DIAGONAL);
    }

    @Test
    void unloadedTerritoryYieldsPartialPathTowardGoal() {
        GridPathfinder.Search s = GridPathfinder.search(new TestWorld(), GridPathfinder.Config.defaults(), 0, 64, 0,
            GridPathfinder.block(500, 64, 0));
        while (s.step(1000) == Status.RUNNING) { }
        assertEquals(Status.PARTIAL, s.status());
        Step last = s.path().get(s.path().size() - 1);
        assertEquals(20, last.x(), "walks to the loaded edge nearest the goal");
    }

    @Test
    void budgetedSearchIsResumable() {
        GridPathfinder.Search s = GridPathfinder.search(new TestWorld(), GridPathfinder.Config.defaults(), -15, 64, -15,
            GridPathfinder.block(15, 64, 15));
        assertEquals(Status.RUNNING, s.step(3));
        while (s.step(3) == Status.RUNNING) { }
        assertEquals(Status.FOUND, s.status());
    }

    @Test
    void neverDigsIntoLavaOrBreaksIntoIt() {
        TestWorld w = new TestWorld();
        w.set(0, 62, 0, Kind.DANGER);
        List<Step> path = run(w, GridPathfinder.Config.defaults().withBreaking(true), 0, 64, 0, GridPathfinder.block(0, 60, 0));
        for (Step s : path) assertFalse(s.x() == 0 && s.z() == 0 && s.y() <= 63 && s.move() == Move.DIG_DOWN,
            "never digs straight down toward lava");
    }

    @Test
    void neverWalksOverAOneBlockGapIntoTheVoid() {
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 20; z++) for (int y = -64; y <= 63; y++) w.set(3, y, z, Kind.OPEN);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertTrue(path.isEmpty(), "a bottomless gap is impassable without parkour/bridging");
    }

    @Test
    void headroomIsRequiredForWalking() {
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 20; z++) w.set(3, 65, z, Kind.SOLID);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty(),
            "a 1-high gap doesn't fit a 1.8 tall player");
    }

    @Test
    void doesNotStepUpWithoutHeadroomAboveStart() {
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 20; z++) w.set(3, 64, z, Kind.SOLID);
        w.set(2, 66, 0, Kind.SOLID);
        for (int z = -20; z <= 20; z++) if (z != 0) w.set(2, 66, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 2, 64, 0, GridPathfinder.block(3, 65, 0));
        assertTrue(path.isEmpty(), "jumping needs the block above our head clear");
    }

    private static TestWorld voidGap(int width) {
        TestWorld w = new TestWorld();
        for (int gx = 3; gx < 3 + width; gx++) for (int z = -20; z <= 20; z++) for (int y = -64; y <= 63; y++) w.set(gx, y, z, Kind.OPEN);
        return w;
    }

    @Test
    void parkourJumpsOneAndTwoBlockGapsOnlyWhenEnabled() {
        GridPathfinder.Config pk = GridPathfinder.Config.defaults().withParkour(true);
        assertTrue(run(voidGap(1), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty());
        assertTrue(run(voidGap(1), pk, 0, 64, 0, GridPathfinder.block(6, 64, 0)).stream().anyMatch(s -> s.move() == Move.PARKOUR));
        assertTrue(run(voidGap(2), pk, 0, 64, 0, GridPathfinder.block(7, 64, 0)).stream().anyMatch(s -> s.move() == Move.PARKOUR));
        assertTrue(run(voidGap(3), pk, 0, 64, 0, GridPathfinder.block(8, 64, 0)).isEmpty(), "3-wide is beyond a safe jump");
    }

    @Test
    void parkourNeedsHeadroomForTheArc() {
        TestWorld w = voidGap(1);
        for (int z = -20; z <= 20; z++) w.set(3, 66, z, Kind.SOLID);
        assertTrue(run(w, GridPathfinder.Config.defaults().withParkour(true), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty());
    }

    @Test
    void bridgingCrossesGapsWithinBudgetAndRecordsPlacements() {
        GridPathfinder.Config br = GridPathfinder.Config.defaults().withPlaceBudget(4);
        List<Step> path = run(voidGap(3), br, 0, 64, 0, GridPathfinder.block(8, 64, 0));
        long bridges = path.stream().filter(s -> s.move() == Move.BRIDGE).count();
        assertEquals(3, bridges, "one placement per gap block, chained off the previous one");
        for (Step s : path) {
            if (s.move() == Move.BRIDGE) assertEquals(GridPathfinder.pack(s.x(), 63, s.z()), s.place());
            else assertEquals(GridPathfinder.NO_PLACE, s.place());
        }
        assertTrue(run(voidGap(3), GridPathfinder.Config.defaults().withPlaceBudget(2), 0, 64, 0,
            GridPathfinder.block(8, 64, 0)).isEmpty(), "out of blocks = no path");
    }

    @Test
    void bridgedBlocksDontLeakIntoOtherBranches() {
        // Goal sits on the far side; with budget 1 only ONE gap cell can be bridged, so a 2-wide gap stays impassable.
        assertTrue(run(voidGap(2), GridPathfinder.Config.defaults().withPlaceBudget(1), 0, 64, 0,
            GridPathfinder.block(7, 64, 0)).isEmpty());
    }

    @Test
    void climbsLadderUpACliff() {
        TestWorld w = new TestWorld();
        // 6-high cliff wall at x=3, ladder on its face at x=2.
        for (int z = -20; z <= 20; z++) for (int y = 64; y <= 69; y++) w.set(3, y, z, Kind.SOLID);
        for (int x = 4; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 64; y <= 69; y++) w.set(x, y, z, Kind.SOLID);
        for (int y = 64; y <= 69; y++) w.set(2, y, 0, Kind.CLIMB);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(4, 70, 0));
        assertFalse(path.isEmpty());
        assertTrue(path.stream().filter(s -> s.move() == Move.CLIMB_UP).count() >= 5);
    }

    @Test
    void climbsDownInsteadOfDropping() {
        TestWorld w = new TestWorld();
        for (int y = 58; y <= 63; y++) w.set(1, y, 0, Kind.CLIMB);
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) if (!(x == 1 && z == 0)) for (int y = 58; y <= 63; y++) w.set(x, y, z, Kind.SOLID);
        for (int z = -20; z <= 20; z++) for (int x = -20; x <= 20; x++) w.set(x, 57, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(1, 58, 0));
        assertFalse(path.isEmpty());
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.CLIMB_DOWN));
    }

    @Test
    void opensDoorsInsteadOfDetouring() {
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 20; z++) w.wall(3, z, 64, 65, Kind.SOLID);
        w.set(3, 64, 0, Kind.DOOR).set(3, 65, 0, Kind.DOOR);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.OPEN_DOOR && s.x() == 3 && s.z() == 0));
    }

    @Test
    void dropsAnyHeightIntoWaterButNotOntoStone() {
        TestWorld w = new TestWorld();
        // A 20-deep pit at x>=3, floor at y=43 (feet 44); one water cell at (6,44,0).
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 44; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 44, 0)).isEmpty(), "20 blocks onto stone is lethal");
        for (int y = 44; y <= 63; y++) w.set(3, y, 0, Kind.OPEN);
        w.set(3, 44, 0, Kind.WATER);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 44, 0));
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.WATER_DROP));
    }

    @Test
    void avoidCostSteersAroundMobs() {
        TestWorld w = new TestWorld() {
            @Override
            public double avoidCost(int x, int y, int z) {
                return x == 3 && Math.abs(z) <= 1 ? 50 : 0;
            }
        };
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertFalse(path.isEmpty());
        for (Step s : path) assertFalse(s.x() == 3 && Math.abs(s.z()) <= 1, "detours around the mob cells");
    }

    @Test
    void heuristicNeverOverestimatesDrops() {
        // Water drop of 20 really costs ~1 + 0.3*20 = 7; the heuristic must not claim more.
        assertTrue(GridPathfinder.block(0, 44, 0).heuristic(0, 64, 0) <= 1.0 + 0.3 * 20 + 1e-9);
        assertTrue(GridPathfinder.yLevel(44).heuristic(5, 64, 5) <= 0.3 * 20 + 1e-9);
    }

    @Test
    void partialPathSkipsDeadEndsAndTrimsTheTail() {
        // Long wall with a far opening: closest-to-goal would hug the wall; the weighted pick heads for the gap.
        TestWorld w = new TestWorld();
        for (int z = -20; z <= 15; z++) w.wall(5, z, 64, 66, Kind.SOLID);
        GridPathfinder.Search s = GridPathfinder.search(w, GridPathfinder.Config.defaults(), 0, 64, 0,
            GridPathfinder.block(400, 64, 0));
        while (s.step(1000) == Status.RUNNING) { }
        assertEquals(Status.PARTIAL, s.status());
        List<Step> p = s.path();
        assertTrue(p.size() >= 2);
        Step last = p.get(p.size() - 1);
        assertTrue(last.x() > 5, "partial path gets past the wall instead of stopping in front of it");
    }

    @Test
    void diagonalAscendSavesASteps() {
        TestWorld w = new TestWorld();
        for (int x = 1; x <= 20; x++) for (int z = 1; z <= 20; z++) w.set(x, 64, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(1, 65, 1));
        assertEquals(2, path.size(), "one diagonal hop up onto the corner block");
        assertEquals(Move.ASCEND, path.get(1).move());
    }

    @Test
    void pillarsOutOfAPitWhenItHasBlocks() {
        TestWorld w = new TestWorld();
        // Standing in a 1x1 shaft 3 deep: walls all round, ground level at feet y=64.
        for (int y = 61; y <= 63; y++) w.set(0, y, 0, Kind.OPEN);
        List<Step> none = run(w, GridPathfinder.Config.defaults(), 0, 61, 0, GridPathfinder.block(2, 64, 0));
        assertTrue(none.isEmpty(), "no way out without blocks");
        List<Step> up = run(w, GridPathfinder.Config.defaults().withPlaceBudget(5), 0, 61, 0, GridPathfinder.block(2, 64, 0));
        // Two towers up to y=63, then a normal step up onto the rim beats a third placement.
        assertEquals(2, up.stream().filter(s -> s.move() == Move.PILLAR).count());
        for (Step s : up) if (s.move() == Move.PILLAR) assertEquals(GridPathfinder.pack(s.x(), s.y() - 1, s.z()), s.place());
    }

    @Test
    void ladderCatchesALongFall() {
        TestWorld w = new TestWorld();
        // 12-deep pit at x>=3 with a ladder column at (3,*,0) from the floor up to 57 (a 7-block fall onto it).
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 52; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        for (int y = 52; y <= 57; y++) w.set(3, y, 0, Kind.CLIMB);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 52, 0));
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.LADDER_CATCH));
    }

    @Test
    void waterBucketClutchOnlyWhenEnabled() {
        TestWorld w = new TestWorld();
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 52; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        GridPathfinder.Goal g = GridPathfinder.block(6, 52, 0);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, g).isEmpty(), "12 blocks onto stone is lethal");
        List<Step> path = run(w, GridPathfinder.Config.defaults().withWaterBucket(true), 0, 64, 0, g);
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.BUCKET_DROP));
    }

    @Test
    void longParkourNeedsARunUp() {
        GridPathfinder.Config pk = GridPathfinder.Config.defaults().withParkour(true).withLongParkour(true);
        assertTrue(run(voidGap(3), pk, 0, 64, 0, GridPathfinder.block(8, 64, 0)).stream().anyMatch(s -> s.move() == Move.PARKOUR),
            "starting 3 blocks back gives a run-up for a 3-gap");
        assertTrue(run(voidGap(3), pk, 2, 64, 0, GridPathfinder.block(8, 64, 0)).isEmpty(), "no run-up standing on the lip");
    }

    @Test
    void parkourUpOntoAHigherLedge() {
        TestWorld w = voidGap(1);
        for (int x = 4; x <= 20; x++) for (int z = -20; z <= 20; z++) w.set(x, 64, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults().withParkour(true), 0, 64, 0, GridPathfinder.block(6, 65, 0));
        assertTrue(path.stream().anyMatch(s -> s.move() == Move.PARKOUR && s.y() == 65));
    }

    @Test
    void slowFloorsAreAvoidedWhenADetourIsCheap() {
        TestWorld w = new TestWorld() {
            @Override
            public double speedFactor(int x, int y, int z) {
                return (x == 3 || x == 4) && Math.abs(z) <= 1 ? 0.4 : 1.0;
            }
        };
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        for (Step s : path) assertFalse((s.x() == 3 || s.x() == 4) && Math.abs(s.z()) <= 1, "2-wide soul sand strip is routed around");
    }

    @Test
    void favoredRouteIsKeptOnReplan() {
        TestWorld w = new TestWorld();
        List<Step> first = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(10, 64, 4));
        long[] fav = first.stream().mapToLong(s -> GridPathfinder.pack(s.x(), s.y(), s.z())).toArray();
        GridPathfinder.Search s = GridPathfinder.search(w, GridPathfinder.Config.defaults(), 0, 64, 0,
            GridPathfinder.block(10, 64, 4), fav);
        while (s.step(500) == Status.RUNNING) { }
        assertEquals(first.stream().map(st -> st.x() + "," + st.z()).toList(),
            s.path().stream().map(st -> st.x() + "," + st.z()).toList(), "equal-cost alternatives don't flip-flop");
    }

    @Test
    void hopCostDetectsAWallPlacedAfterPlanning() {
        TestWorld w = new TestWorld();
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(4, 64, 0));
        Step a = path.get(1), b = path.get(2);
        assertTrue(Double.isFinite(GridPathfinder.hopCost(w, GridPathfinder.Config.defaults(), a, b)));
        w.wall(b.x(), b.z(), 64, 65, Kind.SOLID);
        assertTrue(Double.isInfinite(GridPathfinder.hopCost(w, GridPathfinder.Config.defaults(), a, b)));
        // With breaking on, the hop is still possible but needs breaks that weren't planned: still a replan.
        assertTrue(Double.isInfinite(GridPathfinder.hopCost(w, GridPathfinder.Config.defaults().withBreaking(true), a, b)));
    }

    @Test
    void goalCombinators() {
        GridPathfinder.Goal any = GridPathfinder.anyOf(List.of(GridPathfinder.block(5, 64, 0), GridPathfinder.block(-5, 64, 0)));
        assertTrue(any.reached(-5, 64, 0));
        assertEquals(0.0, any.heuristic(5, 64, 0), 1e-9);
        GridPathfinder.Goal away = GridPathfinder.runAway(10, List.of(new int[]{0, 0}));
        assertFalse(away.reached(3, 64, 0));
        assertTrue(away.reached(10, 64, 0));
        List<Step> path = run(new TestWorld(), GridPathfinder.Config.defaults(), 0, 64, 0, away);
        Step last = path.get(path.size() - 1);
        assertTrue(Math.hypot(last.x(), last.z()) >= 10);
    }

    @Test
    void slabsUnderALowCeilingAreNotStandable() {
        TestWorld w = new TestWorld() {
            @Override
            public double floorHeight(int x, int y, int z) {
                return kind(x, y, z) == Kind.LOW ? 0.5 : 0;
            }
        };
        // A strip of slabs at x=3 with a ceiling at y=66: 2 blocks over the floor but only 1.5 over the slab.
        for (int z = -20; z <= 20; z++) w.set(3, 64, z, Kind.LOW).set(3, 66, z, Kind.SOLID);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty(),
            "a 1.8-tall player doesn't fit on a slab under a 2-high ceiling");
        TestWorld carpet = new TestWorld() {
            @Override
            public double floorHeight(int x, int y, int z) {
                return kind(x, y, z) == Kind.LOW ? 0.0625 : 0;
            }
        };
        for (int z = -20; z <= 20; z++) carpet.set(3, 64, z, Kind.LOW).set(3, 66, z, Kind.SOLID);
        assertFalse(run(carpet, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty(),
            "carpet in a 2-high room is fine");
    }

    @Test
    void noJumpingOffHoney() {
        TestWorld w = new TestWorld() {
            @Override
            public double jumpFactor(int x, int y, int z) {
                return x == 2 && z == 0 ? 0.5 : 1.0;
            }
        };
        // Solid step at x=3 across the whole map: the only honey cell is (2,0); routes must jump from elsewhere.
        for (int z = -20; z <= 20; z++) w.set(3, 64, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 2, 64, 0, GridPathfinder.block(3, 65, 0));
        assertFalse(path.isEmpty());
        assertFalse(path.get(1).move() == Move.ASCEND && path.get(0).x() == 2 && path.get(0).z() == 0,
            "never jumps straight off the honey block");
    }

    @Test
    void neverPlansALongFullySubmergedSwim() {
        TestWorld w = new TestWorld();
        // A 40-long tunnel full of water with a solid ceiling: no air for 40 blocks.
        for (int x = 0; x <= 40; x++) {
            w.set(x, 64, 0, Kind.WATER).set(x, 65, 0, Kind.WATER).set(x, 66, 0, Kind.SOLID);
            for (int z = -20; z <= 20; z++) if (z != 0) w.wall(x, z, 64, 66, Kind.SOLID);
        }
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(40, 64, 0)).isEmpty(),
            "40 blocks without air would drown the player");
    }

    @Test
    void digDownReachesLowerLevelWhenBreakingAllowed() {
        List<Step> path = run(new TestWorld(), GridPathfinder.Config.defaults().withBreaking(true), 0, 64, 0,
            GridPathfinder.block(0, 60, 0));
        assertFalse(path.isEmpty());
        assertTrue(path.stream().allMatch(s -> s.move() == Move.START || s.move() == Move.DIG_DOWN));
    }

    @Test
    void dropsBesideFarmlandInsteadOfOntoIt() {
        // A 2-deep sunken field at x>=3: landing at x=3 tramples farmland, but stepping down at z=4 doesn't.
        TestWorld w = new TestWorld() {
            @Override
            public double trampleCost(int x, int y, int z) {
                return x == 3 && y == 62 && z != 4 ? 25.0 : 0;
            }
        };
        for (int x = 3; x <= 8; x++) for (int z = -20; z <= 20; z++) for (int y = 62; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 62, 0));
        assertFalse(path.isEmpty());
        Step landing = path.stream().filter(s -> s.y() == 62).findFirst().orElseThrow();
        assertFalse(landing.x() == 3 && landing.z() != 4, "landed on farmland at z=" + landing.z());
    }

    /** Slab-topped LOW cells sit 0.5 above their floor; everything else stands flush. */
    private static TestWorld slabWorld() {
        return new TestWorld() {
            @Override
            public double floorHeight(int x, int y, int z) {
                return kind(x, y, z) == Kind.LOW ? 0.5 : 0;
            }
        };
    }

    @Test
    void neverJumpsOntoASlabToppedBlock() {
        // A wall of full blocks at x=3 with bottom slabs on top: 1.5 up, past the jump apex.
        TestWorld w = slabWorld();
        for (int z = -20; z <= 20; z++) w.set(3, 64, z, Kind.SOLID).set(3, 65, z, Kind.LOW);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 65, 0)).isEmpty(),
            "a block with a slab on top can't be jumped onto from the ground");
        // A slab step in front makes it a staircase: slab (walk up 0.5), then a full block (walk up 0.5 again).
        w.set(2, 64, 0, Kind.LOW);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 65, 0));
        assertFalse(path.isEmpty(), "slab stairs climb it");
    }

    @Test
    void stepsUpFromASlabWithoutJumping() {
        // Standing on a slab, the full block beside it is only half a block higher: a step, so honey can't stop it.
        TestWorld w = new TestWorld() {
            @Override
            public double floorHeight(int x, int y, int z) {
                return kind(x, y, z) == Kind.LOW ? 0.5 : 0;
            }

            @Override
            public double jumpFactor(int x, int y, int z) {
                return 0.5;
            }
        };
        w.set(0, 64, 0, Kind.LOW);
        for (int z = -20; z <= 20; z++) w.set(1, 64, z, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(1, 65, 0));
        assertEquals(2, path.size(), "one step up off the slab even where jumping is impossible");
        assertTrue(path.get(1).cost() < GridPathfinder.Config.defaults().jumpCost() + 1.0, "priced as a step, not a jump");
    }

    @Test
    void neverPillarsIntoASlab() {
        TestWorld w = slabWorld();
        for (int y = 61; y <= 63; y++) w.set(0, y, 0, Kind.OPEN);
        w.set(0, 61, 0, Kind.LOW);
        List<Step> up = run(w, GridPathfinder.Config.defaults().withPlaceBudget(5), 0, 61, 0, GridPathfinder.block(2, 64, 0));
        for (Step s : up) {
            if (s.move() != Move.PILLAR) continue;
            assertNotEquals(Kind.LOW, w.kind(GridPathfinder.unpackX(s.place()), GridPathfinder.unpackY(s.place()),
                GridPathfinder.unpackZ(s.place())), "places a block into a slab's cell: " + s);
        }
    }

    @Test
    void longDropOntoThinFlowingWaterIsNotSafe() {
        TestWorld w = new TestWorld() {
            @Override
            public boolean softLanding(int x, int y, int z) {
                return false;
            }
        };
        for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 44; y <= 63; y++) w.set(x, y, z, Kind.OPEN);
        w.set(3, 44, 0, Kind.WATER);
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(3, 44, 0)).isEmpty(),
            "20 blocks onto a flowing sheet hits the floor at full speed");
    }

    @Test
    void waterHangingOverAirIsNotAFloor() {
        TestWorld w = new TestWorld();
        // A walkway of water cells at y=64 over a void (nothing under them down to y=50).
        for (int x = 1; x <= 5; x++) {
            w.set(x, 64, 0, Kind.WATER);
            for (int y = 50; y <= 63; y++) w.set(x, y, 0, Kind.OPEN);
        }
        for (int x = 1; x <= 5; x++) for (int z = -20; z <= 20; z++) if (z != 0) w.wall(x, z, 64, 66, Kind.SOLID);
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertTrue(path.isEmpty(), "can't walk across water that has nothing under it");
    }

    @Test
    void nearGoalHeuristicIsAdmissibleIn3D() {
        // Costs add per axis (>= 1 per block across, >= UP_PER_BLOCK per block up). For (10 across, 10 up) with r=3 the
        // cheapest in-range point is (10-a, 10-b) with a^2+b^2 <= 9, so the true cost is at least 20 - 3*sqrt(2).
        GridPathfinder.Goal g = GridPathfinder.near(10, 74, 0, 3);
        double lowestTrueCost = 20 - 3 * Math.sqrt(2);
        double h = g.heuristic(0, 64, 0);
        assertTrue(h <= lowestTrueCost + 1e-9, "h=" + h + " > " + lowestTrueCost);
        // Diagonal across: (10,10) flat, in-range point (10-2.12, 10-2.12) costs octile(7.88, 7.88).
        GridPathfinder.Goal flat = GridPathfinder.near(10, 64, 10, 3);
        double d = 10 - 3 / Math.sqrt(2);
        assertTrue(flat.heuristic(0, 64, 0) <= GridPathfinder.octile((int) Math.ceil(d), (int) Math.ceil(d)) + 1e-9);
        assertEquals(0.0, g.heuristic(10, 74, 3), 1e-9, "inside the sphere");
    }

    @Test
    void infiniteAvoidCostIsNeverEntered() {
        TestWorld w = new TestWorld() {
            @Override
            public double avoidCost(int x, int y, int z) {
                return x == 3 ? Double.POSITIVE_INFINITY : 0;
            }
        };
        assertTrue(run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0)).isEmpty(),
            "an infinite cost wall is a wall, not a free g=inf node");
    }

    @Test
    void walkingOntoFarmlandIsNotPenalised() {
        // Only landings trample: a plain flat walk across the same cells stays straight.
        TestWorld w = new TestWorld() {
            @Override
            public double trampleCost(int x, int y, int z) {
                return x >= 2 && x <= 4 ? 25.0 : 0;
            }
        };
        List<Step> path = run(w, GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(6, 64, 0));
        assertEquals(7, path.size());
        assertTrue(path.stream().allMatch(s -> s.z() == 0));
    }
}
