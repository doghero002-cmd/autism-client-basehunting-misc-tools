package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * AUTISM-client smooth aim.
 *
 * A thin wrapper over the AUTISM Client's own {@code AutismHumanRotation} engine (the same smooth
 * KillAura rotation the base client uses), applied to looking at blocks/points. Because it reuses
 * the client's built-in human-rotation stream, the yaw/pitch curve matches AUTISM's smoothest
 * built-in aim exactly - far smoother than our hand-rolled easers.
 *
 * One {@code Stream} per module (the stream carries the human state between ticks).
 *
 * Usage:
 *   private final AutismAim aim = new AutismAim();
 *   // each tick: aim.face(mc, targetYaw, targetPitch);  // or aim.faceBlock(mc, pos);
 */
public final class AutismAim {
    private final autismclient.util.AutismHumanRotation.Stream stream = new autismclient.util.AutismHumanRotation.Stream();
    private boolean seeded = false;

    public AutismAim() {}

    /** Re-seed the stream from the player's current view (call on enable so it doesn't head-flick). */
    public void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            autismclient.util.AutismHumanRotation.seed(stream,
                new autismclient.util.AutismRotationUtil.Rotation(mc.player.getYRot(), mc.player.getXRot()));
            seeded = true;
        } else {
            seeded = false;
        }
    }

    /**
     * Smoothly rotate the client's view toward the given yaw/pitch using the AUTISM human-rotation
     * engine. Returns the new yaw/pitch applied.
     */
    public float[] face(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return new float[] { yaw, pitch };
        if (!seeded) reset();
        autismclient.util.AutismRotationUtil.Rotation goal =
            new autismclient.util.AutismRotationUtil.Rotation(yaw, net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        try {
            autismclient.util.AutismRotationUtil.Rotation out =
                autismclient.util.AutismHumanRotation.step(stream, goal, 1.0f, 1.0f, 0.0);
            mc.player.setYRot(out.yaw());
            mc.player.setXRot(net.minecraft.util.Mth.clamp(out.pitch(), -90f, 90f));
            return new float[] { out.yaw(), out.pitch() };
        } catch (Throwable t) {
            // Fallback: direct set (shouldn't happen).
            mc.player.setYRot(yaw);
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
            return new float[] { yaw, pitch };
        }
    }

    /** Smoothly face the centre of a block. Returns the new yaw/pitch. */
    public float[] faceBlock(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return new float[] { 0, 0 };
        Vec3 eye = mc.player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return face(mc, yaw, pitch);
    }

    /** True once the stream has converged on the goal (within the engine's settle band). */
    public boolean isDone(float yaw, float pitch) {
        try {
            autismclient.util.AutismRotationUtil.Rotation cur = autismclient.util.AutismHumanRotation.current(stream);
            if (cur == null) return true;
            float dy = Math.abs(net.minecraft.util.Mth.wrapDegrees(cur.yaw() - yaw));
            float dp = Math.abs(cur.pitch() - pitch);
            return dy < 1.5f && dp < 1.5f;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Clear the stream state (call on disable). */
    public void clear() {
        try { autismclient.util.AutismHumanRotation.clear(stream); } catch (Throwable ignored) {}
        seeded = false;
    }
}
