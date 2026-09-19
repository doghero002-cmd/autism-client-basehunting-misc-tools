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

    // ---- shared smooth-rotation helpers (human turn-rate limiting) ----

    private static final java.util.Random ROT_RNG = new java.util.Random();

    /** Move a linear value toward a target by at most maxStep (e.g. pitch). */
    public static float approach(float current, float target, float maxStep) {
        float d = target - current;
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return current + d;
    }

    /** Move an angle (yaw, degrees) toward a target by at most maxStep, wrapping at ±180. */
    public static float approachAngle(float current, float target, float maxStep) {
        float d = target - current;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return current + d;
    }

    /**
     * Human turn easing (Krypton AimAssist + Water RotateCharacter + Wocky wind/gravity), one
     * central upgrade for every rotation consumer. Instead of a constant maxStep/tick sweep (a
     * known bot signature), the step:
     *  - scales DOWN as you near the target (Water's dist/20 ease - fast flick, slow settle),
     *  - is clamped to never overshoot (Krypton's "toRotate > remaining -> use remaining"),
     *  - occasionally hesitates (Water's 5% micro-pause on big turns),
     *  - carries a smoothed gust (Wocky's wind/gravity: adaptive step + per-tick jitter),
     *  - so the yaw curve reads like a real mouse instead of a metronome.
     *
     * @param current current yaw (deg)
     * @param target  target yaw (deg)
     * @param baseStep the nominal (max) step for a large turn, in degrees
     */
    public static float humanTurn(float current, float target, float baseStep) {
        float remaining = target - current;
        while (remaining > 180f) remaining -= 360f;
        while (remaining < -180f) remaining += 360f;
        float dist = Math.abs(remaining);
        if (dist < 1.0e-4f) return target;

        // Water: ease factor slows near the target (full speed when far, ~20% when very close).
        float ease = Math.min(1.0f, dist / 20.0f);
        float step = baseStep * (0.2f + 0.8f * ease);

        // Wocky wind/gravity: a smoothed per-tick gust so the speed isn't perfectly uniform.
        step += (ROT_RNG.nextFloat() - 0.5f) * (baseStep * 0.35f);

        // Water: 5% micro-hesitation on larger turns.
        if (dist > 5f && ROT_RNG.nextFloat() < 0.05f) step *= 0.1f;

        if (step < 0.15f) step = 0.15f;
        // Krypton: never overshoot - clamp the step to the remaining angle.
        float signed = Math.copySign(Math.min(step, dist), remaining);
        return current + signed;
    }

    /** Pitch variant of {@link #humanTurn} (no wrap). */
    public static float humanTurnPitch(float current, float target, float baseStep) {
        float remaining = target - current;
        float dist = Math.abs(remaining);
        if (dist < 1.0e-4f) return target;
        float ease = Math.min(1.0f, dist / 20.0f);
        float step = baseStep * (0.2f + 0.8f * ease);
        step += (ROT_RNG.nextFloat() - 0.5f) * (baseStep * 0.35f);
        if (dist > 5f && ROT_RNG.nextFloat() < 0.05f) step *= 0.1f;
        if (step < 0.15f) step = 0.15f;
        float signed = Math.copySign(Math.min(step, dist), remaining);
        return current + signed;
    }
}
