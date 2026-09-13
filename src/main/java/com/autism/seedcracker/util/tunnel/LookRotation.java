package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Look-rotation util.
 *
 * Port of the CodeEngine "AutoCrystalModuleUtil.floatArrayOf" (yaw/pitch to look at a point)
 * plus the "AntiAFKModuleUtil" apply-delegate pattern, to Mojang 26.2 mappings. Modules
 * compute the desired yaw/pitch here and apply it through a single choke point so rotation
 * behaviour stays consistent (and can be routed silently if needed).
 */
public final class LookRotation {
    private static final Minecraft mc = Minecraft.getInstance();

    /** Last requested rotation (yaw, pitch), or null when none is being driven. */
    private static volatile float[] requested = null;

    private LookRotation() {}

    /** yaw/pitch that looks from the player's eyes at the given point. */
    public static float[] lookAt(Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        return new float[] { yaw, Mth.clamp(pitch, -90.0F, 90.0F) };
    }

    /** yaw/pitch that looks at an entity's bounding-box centre. */
    public static float[] lookAt(Entity entity) {
        return lookAt(entity.getBoundingBox().getCenter());
    }

    /** Request a rotation (delegated; applied by the engine/mixin layer). */
    public static void apply(float yaw, float pitch) {
        requested = new float[] { yaw, pitch };
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        }
    }

    /** Stop driving rotation. */
    public static void clear() {
        requested = null;
    }

    public static float[] getRequested() {
        return requested;
    }

    public static boolean isActive() {
        return requested != null;
    }
}
