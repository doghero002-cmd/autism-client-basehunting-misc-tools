package com.autism.seedcracker.util.pure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Pure WALK_2X1 tunnel decision planner (unit tested - no Minecraft world/registry access).
 *
 * Extracts the decision core of TunnelBaseFinder's walk-tunnel mode: given a boolean view of the
 * world (adapter in the module), decide this tick's action. The module executes decisions (keys,
 * aim, dig) and owns temporal state (commit ticks, dig pacing); the planner owns the geometry.
 *
 * Decision order mirrors the field-tested tickWalkTunnel exactly:
 *  1. liquid within 3 ahead (feet/head/floor) or dripping overhead -> GO_AROUND
 *  2. bedrock wall ahead -> GO_AROUND
 *  3. below target Y (bedrock hole) -> ESCAPE_HOLE
 *  4. dig target = head block first, else feet
 *  5. breaking it would expose a liquid/gravity pocket -> GO_AROUND
 *  6. target exists -> DIG, else -> WALK
 */
public final class TunnelPlanner {

    private TunnelPlanner() {}

    /** Boolean world view; the module adapts BlockStates to these (registry-free for tests). */
    public interface World {
        boolean air(BlockPos pos);
        boolean liquid(BlockPos pos);
        boolean bedrock(BlockPos pos);
        /** Gravity block (sand/gravel/concrete powder/anvil). */
        boolean falling(BlockPos pos);
        /** Full hazard classification (liquid, gravity, contact hazards). */
        boolean hazard(BlockPos pos);
    }

    public enum Action { GO_AROUND, ESCAPE_HOLE, DIG, WALK }

    /** The planned action; {@code digTarget} only for DIG, {@code reason} only for GO_AROUND. */
    public record Plan(Action action, BlockPos digTarget, String reason) {
        static Plan goAround(String reason) { return new Plan(Action.GO_AROUND, null, reason); }
        static Plan dig(BlockPos target) { return new Plan(Action.DIG, target, null); }
        static final Plan WALK = new Plan(Action.WALK, null, null);
        static final Plan ESCAPE = new Plan(Action.ESCAPE_HOLE, null, null);
    }

    public static Plan plan(World w, BlockPos playerPos, Direction dir,
                            boolean escapeHoles, int targetY) {
        BlockPos aheadFeet = playerPos.relative(dir);
        BlockPos aheadHead = aheadFeet.above();

        // 1. Liquid scan: 3 ahead at feet/head/floor, plus the ceiling dripping down.
        for (int d = 1; d <= 3; d++) {
            BlockPos f = playerPos.relative(dir, d);
            if (w.liquid(f) || w.liquid(f.above()) || w.liquid(f.below())) {
                return Plan.goAround("lava");
            }
        }
        if (w.liquid(aheadHead.above()) || w.liquid(aheadHead.above(2))) {
            return Plan.goAround("lava");
        }

        // 2. Bedrock wall ahead (bedrock is excluded from dig targets, so without this we'd
        // walk into the wall forever).
        if (w.bedrock(aheadFeet) || w.bedrock(aheadHead)) {
            return Plan.goAround("bedrock");
        }

        // 3. Fallen below the tunnel line: pillar back up first.
        if (escapeHoles && playerPos.getY() < targetY) {
            return Plan.ESCAPE;
        }

        // 4. Dig target: head first (tunnel clears top-down), else feet.
        boolean headSolid = !w.air(aheadHead) && !w.bedrock(aheadHead);
        boolean feetSolid = !w.air(aheadFeet) && !w.bedrock(aheadFeet);
        BlockPos target = headSolid ? aheadHead : (feetSolid ? aheadFeet : null);

        // 5. Pocket check: never break a block hiding a liquid or a gravity block behind it.
        if (target != null && breakExposesHazard(w, target, dir)) {
            return Plan.goAround("hazard pocket");
        }

        return target != null ? Plan.dig(target) : Plan.WALK;
    }

    /**
     * True if breaking the block exposes a hazard: liquid behind any face except the one we
     * break from, or a gravity block directly above (falls into the fresh hole, on us).
     */
    public static boolean breakExposesHazard(World w, BlockPos target, Direction tunnelDir) {
        Direction fromPlayer = tunnelDir != null ? tunnelDir.getOpposite() : null;
        for (Direction d : Direction.values()) {
            if (d == fromPlayer) continue;
            BlockPos n = target.relative(d);
            if (w.liquid(n)) return true;
            if (d == Direction.UP && w.falling(n)) return true;
        }
        return false;
    }

    /** True if the lane one block to the side has no liquid at feet/head/floor. */
    public static boolean isLaneSafe(World w, BlockPos playerPos, Direction sideDir) {
        BlockPos p = playerPos.relative(sideDir);
        return !w.liquid(p) && !w.liquid(p.above()) && !w.liquid(p.below());
    }

    /**
     * Score a lane for going around: -1 unusable (liquid, bedrock at feet+head, or a 2-deep
     * drop), higher = clearer. Checks the strafe cell plus 3 forward cells.
     */
    public static int laneScore(World w, BlockPos playerPos, Direction lane, Direction tunnelDir) {
        BlockPos base = playerPos.relative(lane);
        if (w.liquid(base) || w.liquid(base.above()) || w.liquid(base.below())) return -1;
        if (w.bedrock(base) || w.bedrock(base.above())) return -1;
        if (w.air(base.below()) && w.air(base.below(2))) return -1; // drop
        int score = 0;
        for (int i = 1; i <= 3; i++) {
            BlockPos fwd = base.relative(tunnelDir, i);
            if (w.liquid(fwd) || w.liquid(fwd.above()) || w.liquid(fwd.below())) break;
            if (w.bedrock(fwd) && w.bedrock(fwd.above())) break;
            score++;
            if (w.air(fwd)) score++;
            if (w.air(fwd.above())) score++;
        }
        return score;
    }

    /** Pick the better lane by score, or null when both are unusable. */
    public static Direction chooseLane(World w, BlockPos playerPos, Direction tunnelDir) {
        Direction right = tunnelDir.getClockWise();
        Direction left = tunnelDir.getCounterClockWise();
        int rightScore = laneScore(w, playerPos, right, tunnelDir);
        int leftScore = laneScore(w, playerPos, left, tunnelDir);
        if (rightScore < 0 && leftScore < 0) return null;
        return rightScore >= leftScore ? right : left;
    }
}
