package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * ThunderHack InteractionUtility "Interact.Legit": find a point on a block face that is genuinely
 * visible from the player's eyes (validated with a real collider raycast), instead of blindly
 * clicking the face centre. Grim's PositionPlace check raycasts our reported hit vec - a centre
 * point that is actually occluded by a neighbouring block fails it; a scanned visible point never
 * does.
 *
 * The face is scanned on a 0.1 grid inset to .15...85 (never the exact edge - vanilla rays don't
 * land on edges). Returns null when no point on the face is visible within range.
 */
public final class LegitHitPoint {

    private LegitHitPoint() {}

    /**
     * A raycast-validated visible point on {@code face} of block {@code bp}, or null.
     *
     * @param range max distance from eyes (blocks)
     */
    public static Vec3 find(Minecraft mc, BlockPos bp, Direction face, double range) {
        if (mc.player == null || mc.level == null) return null;
        double minU, maxU, minV, maxV;
        // Face plane coordinate: which axis is fixed, and at 0 or 1.
        double plane = (face == Direction.UP || face == Direction.EAST || face == Direction.SOUTH) ? 1.0 : 0.0;
        minU = 0.15; maxU = 0.85; minV = 0.15; maxV = 0.85;

        for (double u = minU; u <= maxU; u += 0.1) {
            for (double v = minV; v <= maxV; v += 0.1) {
                Vec3 point = switch (face.getAxis()) {
                    case X -> new Vec3(bp.getX() + plane, bp.getY() + u, bp.getZ() + v);
                    case Y -> new Vec3(bp.getX() + u, bp.getY() + plane, bp.getZ() + v);
                    case Z -> new Vec3(bp.getX() + u, bp.getY() + v, bp.getZ() + plane);
                };
                if (isVisible(mc, point, bp, range)) return point;
            }
        }
        return null;
    }

    /** THack shouldSkipPoint inverted: in range AND the eye->point ray hits our block first. */
    private static boolean isVisible(Minecraft mc, Vec3 point, BlockPos bp, double range) {
        Vec3 eyes = mc.player.getEyePosition();
        double distSq = eyes.distanceToSqr(point);
        if (distSq > range * range) return false;
        BlockHitResult result = mc.level.clip(new ClipContext(
            eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        // Ray reaching the target block (or nothing in the way) = visible.
        return result == null
            || result.getType() != HitResult.Type.BLOCK
            || result.getBlockPos().equals(bp);
    }
}
