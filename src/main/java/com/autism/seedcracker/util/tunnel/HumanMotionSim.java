package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Human motion step engine.
 *
 * Faithful port of the CodeEngine "HumanMotionSim" util (dev.nyx.util.HumanMotionSim) to
 * Mojang 26.2 mappings. Walks the player toward a target by lerping yaw only, driving the
 * real movement keys (forward/sprint/jump/sneak), with:
 *   - ARRIVED when within reach distance,
 *   - a turn-in-place gate (won't walk until roughly facing the target),
 *   - MINE_THROUGH detection when the 2-high space ahead is solid,
 *   - EDGE_STOP / FALL_HAZARD detection so it won't walk off a drop,
 *   - jump when only the feet block is solid.
 *
 * Rotation is delegated to the shared look-at util (AutoCrystal-style yaw/pitch), matching
 * how the source routes rotation through AntiAFKModuleUtil.
 */
public final class HumanMotionSim {
    private static final Minecraft mc = Minecraft.getInstance();

    private static boolean forwardDown = false;
    private static boolean jumpDown = false;
    private static boolean sprintDown = false;
    private static boolean sneakDown = false;

    private static BlockPos mineTarget = null;
    private static int fallHeight = 0;
    private static BlockPos edgePos = null;
    private static Direction edgeDir = null;

    private HumanMotionSim() {}

    public enum StepResult {
        ADVANCING, ARRIVED, EDGE_STOP, FALL_HAZARD, BLOCKED, MINE_THROUGH, NO_WORLD
    }

    public static int getFallHeight() { return fallHeight; }
    public static BlockPos getEdgePos() { return edgePos; }
    public static Direction getEdgeDir() { return edgeDir; }
    public static BlockPos getMineTarget() { return mineTarget; }

    /** A point {@code dist} blocks in front of the player along their current yaw. */
    public static Vec3 pointAhead(double dist) {
        LocalPlayer p = mc.player;
        if (p == null) return null;
        Vec3 pos = p.position();
        float yawRad = (float) Math.toRadians(p.getYRot());
        double sin = -Mth.sin(yawRad);
        double cos = Mth.cos(yawRad);
        return new Vec3(pos.x + sin * dist, pos.y, pos.z + cos * dist);
    }

    /** Step toward the target with a 1-block fall tolerance. */
    public static StepResult step(Vec3 target, double reachDist, double turnGateDeg, int turnSpeedTicks) {
        return stepInternal(target, reachDist, turnGateDeg, turnSpeedTicks, 1, false);
    }

    /** Step toward the target with a configurable fall tolerance. */
    public static StepResult step(Vec3 target, double reachDist, double turnGateDeg, int turnSpeedTicks, int maxFall) {
        return stepInternal(target, reachDist, turnGateDeg, turnSpeedTicks, Math.max(1, maxFall), true);
    }

    private static StepResult stepInternal(Vec3 target, double reachDist, double turnGateDeg, int turnSpeedTicks, int maxFall, boolean trackFall) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        Options opt = mc.options;
        if (p == null || level == null || opt == null || target == null) {
            releaseAll();
            return StepResult.NO_WORLD;
        }

        fallHeight = 0;
        edgePos = null;
        edgeDir = null;

        Vec3 pos = p.position();
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distSq = dx * dx + dz * dz;
        if (distSq <= reachDist * reachDist) {
            mineTarget = null;
            releaseAll();
            return StepResult.ARRIVED;
        }

        float targetYaw = Mth.wrapDegrees((float) (Math.atan2(-dx, dz) * (180.0 / Math.PI)));
        int turnTicks = Math.max(1, turnSpeedTicks);
        float newYaw = Mth.rotLerp(1.0F / turnTicks, p.getYRot(), targetYaw);
        LookRotation.apply(newYaw, 0.0F);
        float yawErr = Math.abs(Mth.wrapDegrees(targetYaw - newYaw));
        if (yawErr > turnGateDeg) {
            setForward(false);
            setJump(false);
            setSprint(false);
            return StepResult.ADVANCING;
        }

        double invDist = 1.0 / Math.sqrt(distSq);
        double nx = dx * invDist;
        double nz = dz * invDist;
        BlockPos feetAhead = BlockPos.containing(pos.x + nx * 0.5, pos.y, pos.z + nz * 0.5);
        BlockPos probe = BlockPos.containing(pos.x + nx * 0.7, pos.y, pos.z + nz * 0.7);
        BlockState feetState = level.getBlockState(feetAhead);
        BlockState headState = level.getBlockState(feetAhead.above());
        boolean feetSolid = feetState.isSolid() && !feetState.canBeReplaced();
        boolean headSolid = headState.isSolid() && !headState.canBeReplaced();

        if (feetSolid && headSolid) {
            mineTarget = feetAhead.above();
            setForward(false);
            setJump(false);
            setSprint(false);
            return StepResult.MINE_THROUGH;
        } else if (feetSolid) {
            mineTarget = null;
            setJump(true);
            setForward(true);
            setSprint(true);
            return StepResult.ADVANCING;
        } else {
            setJump(false);
            if (p.onGround()) {
                int probeDepth = maxFall + 8;
                int drop = 0;
                for (int i = 1; i <= probeDepth; i++) {
                    BlockState below = level.getBlockState(probe.below(i));
                    if (!below.isAir() && below.getFluidState().isEmpty()) break;
                    drop = i;
                }
                if (drop > maxFall) {
                    fallHeight = drop;
                    edgeDir = directionOf(nx, nz);
                    edgePos = p.blockPosition().below();
                    mineTarget = null;
                    setForward(false);
                    setSprint(false);
                    return trackFall ? StepResult.FALL_HAZARD : StepResult.EDGE_STOP;
                }
            }

            mineTarget = null;
            setForward(true);
            setSprint(true);
            return StepResult.ADVANCING;
        }
    }

    /** Release every movement key the engine may be holding. */
    public static void releaseAll() {
        setForward(false);
        setJump(false);
        setSprint(false);
        setSneak(false);
    }

    private static void setForward(boolean down) {
        if (down != forwardDown) {
            Options opt = mc.options;
            if (opt != null) { opt.keyUp.setDown(down); forwardDown = down; }
        }
    }

    private static void setJump(boolean down) {
        if (down != jumpDown) {
            Options opt = mc.options;
            if (opt != null) { opt.keyJump.setDown(down); jumpDown = down; }
        }
    }

    private static void setSprint(boolean down) {
        if (down != sprintDown) {
            Options opt = mc.options;
            if (opt != null) { opt.keySprint.setDown(down); sprintDown = down; }
        }
    }

    private static void setSneak(boolean down) {
        if (down != sneakDown) {
            Options opt = mc.options;
            if (opt != null) { opt.keyShift.setDown(down); sneakDown = down; }
        }
    }

    private static Direction directionOf(double nx, double nz) {
        if (Math.abs(nx) >= Math.abs(nz)) {
            return nx >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return nz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }
}
