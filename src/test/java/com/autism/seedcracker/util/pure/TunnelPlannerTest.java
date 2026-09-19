package com.autism.seedcracker.util.pure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.autism.seedcracker.util.pure.TunnelPlanner.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelPlannerTest {

    /** Scriptable world: everything defaults to solid stone; mark cells air/liquid/bedrock/falling. */
    private static final class FakeWorld implements TunnelPlanner.World {
        final Set<BlockPos> air = new HashSet<>();
        final Set<BlockPos> liquid = new HashSet<>();
        final Set<BlockPos> bedrock = new HashSet<>();
        final Set<BlockPos> falling = new HashSet<>();

        FakeWorld setAir(BlockPos... p) { for (BlockPos b : p) air.add(b.immutable()); return this; }
        FakeWorld setLiquid(BlockPos... p) { for (BlockPos b : p) liquid.add(b.immutable()); return this; }
        FakeWorld setBedrock(BlockPos... p) { for (BlockPos b : p) bedrock.add(b.immutable()); return this; }
        FakeWorld setFalling(BlockPos... p) { for (BlockPos b : p) falling.add(b.immutable()); return this; }

        @Override public boolean air(BlockPos pos) { return air.contains(pos); }
        @Override public boolean liquid(BlockPos pos) { return liquid.contains(pos); }
        @Override public boolean bedrock(BlockPos pos) { return bedrock.contains(pos); }
        @Override public boolean falling(BlockPos pos) { return falling.contains(pos); }
        @Override public boolean hazard(BlockPos pos) { return liquid.contains(pos) || falling.contains(pos); }
    }

    private static final BlockPos P = new BlockPos(0, 10, 0);
    private static final Direction N = Direction.NORTH; // ahead = z-1

    private static BlockPos ahead(int d) { return P.relative(N, d); }

    @Test
    void solidWallAheadDigsHeadFirst() {
        FakeWorld w = new FakeWorld();
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(DIG, plan.action());
        assertEquals(ahead(1).above(), plan.digTarget(), "head block clears before feet");
    }

    @Test
    void headClearFeetSolidDigsFeet() {
        FakeWorld w = new FakeWorld().setAir(ahead(1).above());
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(DIG, plan.action());
        assertEquals(ahead(1), plan.digTarget());
    }

    @Test
    void clearTunnelWalks() {
        FakeWorld w = new FakeWorld().setAir(ahead(1), ahead(1).above());
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(WALK, plan.action());
    }

    @Test
    void lavaAheadGoesAround() {
        // Lava 3 blocks ahead at feet level - must be caught by the deep scan.
        FakeWorld w = new FakeWorld().setLiquid(ahead(3));
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(GO_AROUND, plan.action());
        assertEquals("lava", plan.reason());
    }

    @Test
    void lavaDrippingOverheadGoesAround() {
        FakeWorld w = new FakeWorld().setLiquid(ahead(1).above(2));
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(GO_AROUND, plan.action());
    }

    @Test
    void bedrockWallGoesAround() {
        FakeWorld w = new FakeWorld().setBedrock(ahead(1), ahead(1).above());
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(GO_AROUND, plan.action());
        assertEquals("bedrock", plan.reason());
    }

    @Test
    void belowTargetYEscapes() {
        FakeWorld w = new FakeWorld();
        var plan = TunnelPlanner.plan(w, P, N, true, 20); // player at y=10, target 20
        assertEquals(ESCAPE_HOLE, plan.action());
    }

    @Test
    void escapeDisabledIgnoresY() {
        FakeWorld w = new FakeWorld();
        var plan = TunnelPlanner.plan(w, P, N, false, 20);
        assertNotEquals(ESCAPE_HOLE, plan.action());
    }

    @Test
    void gravitySandAboveDigBlockGoesAround() {
        // Gravity sand on top of the head dig block falls into the fresh hole (and on us).
        FakeWorld w = new FakeWorld().setFalling(ahead(1).above(2));
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(GO_AROUND, plan.action());
        assertEquals("hazard pocket", plan.reason());
    }

    @Test
    void sideLavaPocketDetected() {
        // Lava to the EAST of the head dig block - not in the forward scan rows, only the
        // pocket check sees it.
        FakeWorld w = new FakeWorld().setLiquid(ahead(1).above().relative(Direction.EAST));
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        assertEquals(GO_AROUND, plan.action());
        assertEquals("hazard pocket", plan.reason());
    }

    @Test
    void pocketOnPlayerSideIgnored() {
        // "Hazard" on OUR side of the dig block (south = toward player) must not trigger:
        // that's where we stand.
        FakeWorld w = new FakeWorld().setLiquid(ahead(1).above().relative(N.getOpposite()));
        var plan = TunnelPlanner.plan(w, P, N, false, 0);
        // South of the head block at distance-1 is the player's own head cell... which the
        // forward scan doesn't cover; the pocket check must skip it -> normal DIG.
        assertEquals(DIG, plan.action());
    }

    @Test
    void laneScoringPrefersOpenLane() {
        // Right lane (west when facing north... actually east) fully open, left solid.
        Direction right = N.getClockWise();
        FakeWorld w = new FakeWorld();
        BlockPos rBase = P.relative(right);
        // Open the right lane's forward cells.
        w.setAir(rBase.relative(N, 1), rBase.relative(N, 1).above(),
              rBase.relative(N, 2), rBase.relative(N, 2).above(),
              rBase.relative(N, 3), rBase.relative(N, 3).above());
        Direction lane = TunnelPlanner.chooseLane(w, P, N);
        assertEquals(right, lane);
    }

    @Test
    void bothLanesLavaReturnsNull() {
        Direction right = N.getClockWise();
        Direction left = N.getCounterClockWise();
        FakeWorld w = new FakeWorld().setLiquid(P.relative(right), P.relative(left));
        assertNull(TunnelPlanner.chooseLane(w, P, N));
    }

    @Test
    void laneWithDropIsUnusable() {
        Direction right = N.getClockWise();
        FakeWorld w = new FakeWorld();
        BlockPos rBase = P.relative(right);
        w.setAir(rBase.below(), rBase.below(2)); // 2-deep drop
        assertEquals(-1, TunnelPlanner.laneScore(w, P, right, N));
    }

    @Test
    void laneThroughBedrockIsUnusable() {
        Direction right = N.getClockWise();
        FakeWorld w = new FakeWorld();
        BlockPos rBase = P.relative(right);
        w.setBedrock(rBase, rBase.above());
        assertEquals(-1, TunnelPlanner.laneScore(w, P, right, N));
    }
}
