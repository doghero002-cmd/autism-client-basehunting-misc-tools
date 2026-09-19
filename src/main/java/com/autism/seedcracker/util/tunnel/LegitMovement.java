package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Legit movement / rotation engine.
 *
 * A fusion of the two client movement implementations we reference, ported to Mojang 26.2 and
 * the AUTISM API:
 *
 *  - wocky-client (lol.ethane) {@code LegitRotationHelper}: time-delta eased rotation toward a
 *    target with a small per-update jitter and a speed that scales with the turn angle, plus a
 *    "isRotationsDone" convergence check. Reads as a human mouse instead of a per-tick snap.
 *  - Water Client {@code RotationUtil.smoothRotate} / {@code setSilentRotation}: a bounded
 *    per-call step toward the target and silent-rotation routing so the server sees the facing
 *    without the client camera ever flicking.
 *
 * The engine drives the *facing* for the tunnel/base-finder modules. When {@code silent} is on,
 * the facing is applied to outgoing movement packets via {@link SilentRotation} and the camera
 * is left alone; otherwise the camera is eased with the legit timing so it still looks human.
 */
public final class LegitMovement {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Random rng = new Random();

    // wocky LegitRotationHelper tuning.
    private static final float MIN_ROT_SPEED = 2.0f;   // deg per (scaled) update for tiny turns
    private static final float MAX_ROT_SPEED = 8.0f;   // cap for large flicks
    private static final float YAW_JITTER = 1.5f;
    private static final float PITCH_JITTER = 2.0f;
    private static final float DONE_EPS = 1.0f;

    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private long lastUpdateMs;
    private boolean initialized;
    // Wocky AdvancedRotationModel wind/gravity state (adaptive velocity easing).
    private float velYaw = 0f, velPitch = 0f;
    private float windYaw = 0f, windPitch = 0f;
    private float avgRecentYaw = 0f;
    // AlphaDLC HumanAim state: reaction delay + ramp on target switch, speed-jitter EMA.
    private float lastGoalYaw = Float.NaN, lastGoalPitch = Float.NaN;
    private long reactionStart = 0L;
    private long reactionDelay = 0L;
    private long rampDuration = 160L;
    private float speedJitterValue = 1.0f;

    public LegitMovement() {
        reset();
    }

    /** Re-seed from the player's current view (call on enable so it doesn't head-flick). */
    public final void reset() {
        if (mc.player != null) {
            currentYaw = mc.player.getYRot();
            currentPitch = mc.player.getXRot();
        } else {
            currentYaw = 0.0f;
            currentPitch = 0.0f;
        }
        targetYaw = currentYaw;
        targetPitch = currentPitch;
        lastUpdateMs = System.currentTimeMillis();
        velYaw = 0f; velPitch = 0f; windYaw = 0f; windPitch = 0f; avgRecentYaw = 0f;
        lastGoalYaw = Float.NaN; lastGoalPitch = Float.NaN;
        reactionStart = 0L; reactionDelay = 0L; speedJitterValue = 1.0f;
        initialized = true;
    }

    // ---- AlphaDLC HumanAim components ----

    /**
     * 0..1 ramp after a "target switch" (goal moved >15 deg): zero during a random human reaction
     * delay (90-230ms), then cubic-out ease over 120-220ms. Makes new aims start late + soft
     * instead of instantly tracking, like a person noticing the new target.
     */
    private float reactionFactor(float goalYaw, float goalPitch) {
        long now = System.currentTimeMillis();
        boolean switched = Float.isNaN(lastGoalYaw)
            || Math.abs(angleDiff(lastGoalYaw, goalYaw)) > 15f
            || Math.abs(lastGoalPitch - goalPitch) > 15f;
        if (switched) {
            reactionStart = now;
            reactionDelay = 90L + rng.nextInt(140);   // 90-230ms
            rampDuration = 120L + rng.nextInt(100);   // 120-220ms
        }
        lastGoalYaw = goalYaw;
        lastGoalPitch = goalPitch;
        long elapsed = now - reactionStart;
        if (elapsed <= reactionDelay) return 0.0f;
        float t = (float) (elapsed - reactionDelay) / (float) rampDuration;
        if (t >= 1.0f) return 1.0f;
        return (float) (1.0 - Math.pow(1.0 - t, 3.0)); // cubic-out
    }

    /** Dual-sine idle noise: two incommensurate frequencies so it never visibly loops. */
    private static float noiseYaw() {
        double t = System.nanoTime() / 1.0e9;
        return (float) (Math.sin(t * 2.3) * 0.16 + Math.sin(t * 5.7 + 1.1) * 0.05);
    }

    private static float noisePitch() {
        double t = System.nanoTime() / 1.0e9;
        return (float) (Math.sin(t * 1.9 + 0.7) * 0.09 + Math.sin(t * 4.3 + 2.2) * 0.03);
    }

    /** Slow down for turns outside the "FOV comfort zone": 1.0 at <=30 deg down to 0.55 at >=120. */
    private static float fovFactor(float diffYaw, float diffPitch) {
        float dist = (float) Math.hypot(diffYaw, diffPitch);
        if (dist <= 30.0f) return 1.0f;
        if (dist >= 120.0f) return 0.55f;
        return 1.0f - (dist - 30.0f) / 90.0f * 0.45f;
    }

    /** Gaussian speed wobble smoothed by an EMA - aim speed drifts 0.85-1.15x over time. */
    private float speedJitter() {
        float target = (float) (1.0 + rng.nextGaussian() * 0.08);
        target = Mth.clamp(target, 0.85f, 1.15f);
        speedJitterValue += (target - speedJitterValue) * 0.08f;
        return speedJitterValue;
    }

    /**
     * Ease toward the desired yaw/pitch using wocky's time-delta, angle-scaled speed + jitter.
     * Returns the new current yaw/pitch (also stored internally).
     */
    public float[] update(float yaw, float pitch) {
        if (!initialized) reset();
        long now = System.currentTimeMillis();
        float dt = (now - lastUpdateMs) / 1000.0f;
        lastUpdateMs = now;
        if (mc.player == null) return new float[] { currentYaw, currentPitch };
        // Clamp dt so a lag spike doesn't teleport the view.
        if (dt <= 0f) dt = 0.001f;
        if (dt > 0.1f) dt = 0.1f;

        // AlphaDLC HumanAim: dual-sine drift replaces white-noise jitter (smooth, never loops).
        targetYaw = yaw + noiseYaw() + (rng.nextFloat() * 2f - 1f) * YAW_JITTER * 0.4f;
        targetPitch = Mth.clamp(pitch + noisePitch() + (rng.nextFloat() * 2f - 1f) * PITCH_JITTER * 0.4f, -90f, 90f);

        float yawDist = Math.abs(angleDiff(currentYaw, targetYaw));
        float pitchDist = Math.abs(currentPitch - targetPitch);
        float maxDist = Math.max(yawDist, pitchDist);
        float speed = rotationSpeed(maxDist);
        // Reaction ramp (late+soft start on new targets), FOV comfort, drifting speed wobble.
        speed *= reactionFactor(yaw, pitch) * fovFactor(yawDist, pitchDist) * speedJitter();

        currentYaw = approachAngle(currentYaw, targetYaw, speed * dt * 20f);
        currentPitch = approach(currentPitch, targetPitch, speed * dt * 20f);
        return new float[] { currentYaw, currentPitch };
    }

    /**
     * Wocky AdvancedRotationModel: wind + gravity velocity-based easing. Instead of a fixed lerp
     * speed, the view carries a velocity that:
     *  - is pulled toward the target by "gravity" (stronger when far, like a wrist flick),
     *  - is nudged by a decaying random "wind" gust each tick (organic wobble),
     *  - is capped by an ADAPTIVE max velocity that grows with turn size and recent turn speed
     *    (so big fast flicks are allowed, small slow ones aren't),
     *  - couples yaw<->pitch (moving one bleeds a little into the other, like a real hand),
     * producing natural accel/decel that adapts to how the player has been turning. Use for the
     * most human-feeling aim (SchematicBuilder placement, combat-adjacent look).
     */
    public float[] updateWindGravity(float yaw, float pitch) {
        if (!initialized) reset();
        long now = System.currentTimeMillis();
        float dt = (now - lastUpdateMs) / 1000.0f;
        lastUpdateMs = now;
        if (mc.player == null) return new float[] { currentYaw, currentPitch };
        if (dt <= 0f) dt = 0.001f;
        if (dt > 0.1f) dt = 0.1f;
        float step = dt * 20f; // ticks-fraction

        float dYaw = angleDiff(currentYaw, yaw);
        float dPitch = pitch - currentPitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (dist < 1.0e-4f) { velYaw = 0; velPitch = 0; return new float[] { currentYaw, currentPitch }; }

        // Adaptive max velocity (Wocky): base + turn-size + recent-speed * random.
        float maxVel = 3.5f + Math.abs(dYaw) * 0.4375f + avgRecentYaw * (0.8f + rng.nextFloat() * 0.8f);

        // Wind gust, decayed by sqrt(3), plus gravity pull toward target.
        windYaw = (float) (windYaw / Math.sqrt(3.0) + (rng.nextFloat() * 2 - 1) * 1.2f / Math.sqrt(5.0));
        windPitch = (float) (windPitch / Math.sqrt(3.0) + (rng.nextFloat() * 2 - 1) * 1.2f / Math.sqrt(5.0));
        float gravity = 0.6f;
        velYaw += (windYaw + gravity * (dYaw / dist) * Math.abs(dYaw)) * step;
        velPitch += (windPitch + gravity * (dPitch / dist) * Math.abs(dPitch)) * step;

        // Clamp velocity to the adaptive cap; add yaw<->pitch cross-coupling.
        velYaw = Mth.clamp(velYaw, -maxVel, maxVel);
        velPitch = Mth.clamp(velPitch, -maxVel * 0.6f, maxVel * 0.6f);
        float cross = velYaw * 0.05f;

        float newYaw = currentYaw + velYaw * step;
        float newPitch = currentPitch + (velPitch + cross) * step;

        // Snap + bleed off velocity when we reach the target (no oscillation).
        if (Math.abs(angleDiff(newYaw, yaw)) < 0.5f) { newYaw = yaw; velYaw *= 0.3f; }
        if (Math.abs(newPitch - pitch) < 0.5f) { newPitch = pitch; velPitch *= 0.3f; }

        avgRecentYaw = avgRecentYaw * 0.85f + Math.abs(angleDiff(currentYaw, newYaw)) * 0.15f;
        currentYaw = newYaw;
        currentPitch = Mth.clamp(newPitch, -90f, 90f);
        return new float[] { currentYaw, currentPitch };
    }

    /** Human coarse/obstacle turn: distance-eased + hesitation + no-overshoot (not a metronome). */
    public float smoothYaw(float target, float stepDeg) {
        currentYaw = LookRotation.humanTurn(currentYaw, target, stepDeg);
        return currentYaw;
    }

    public float smoothPitch(float target, float stepDeg) {
        currentPitch = Mth.clamp(LookRotation.humanTurnPitch(currentPitch, target, stepDeg), -90f, 90f);
        return currentPitch;
    }

    /** True once the eased rotation has converged on the target (wocky isRotationsDone). */
    public boolean isRotationsDone() {
        return Math.abs(angleDiff(currentYaw, targetYaw)) < DONE_EPS
            && Math.abs(currentPitch - targetPitch) < DONE_EPS;
    }

    public float yaw() { return currentYaw; }
    public float pitch() { return currentPitch; }

    /**
     * Apply the current facing. When {@code silent} is on, route through {@link SilentRotation}
     * (packets only); otherwise ease the camera itself.
     */
    public void apply(boolean silent) {
        if (mc.player == null) return;
        if (silent) {
            SilentRotation.apply(currentYaw, Mth.clamp(currentPitch, -90f, 90f));
        } else {
            SilentRotation.clear();
            mc.player.setYRot(currentYaw);
            mc.player.setXRot(Mth.clamp(currentPitch, -90f, 90f));
        }
    }

    /** Convenience: update toward a block's centre, then apply. */
    public void faceBlock(BlockPos pos, boolean silent) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        update(yaw, pitch);
        apply(silent);
    }

    // ---- helpers ----
    private static float rotationSpeed(float angleDeg) {
        float s = MIN_ROT_SPEED + (angleDeg / 90.0f) * (MAX_ROT_SPEED - MIN_ROT_SPEED);
        return Math.min(s, MAX_ROT_SPEED);
    }

    private static float approach(float current, float target, float maxStep) {
        float d = target - current;
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return current + d;
    }

    private static float approachAngle(float current, float target, float maxStep) {
        float d = angleDiff(current, target);
        if (Math.abs(d) <= maxStep) return target;
        return current + Math.copySign(maxStep, d);
    }

    private static float angleDiff(float from, float to) {
        float d = (to - from) % 360.0f;
        if (d > 180.0f) d -= 360.0f;
        else if (d < -180.0f) d += 360.0f;
        return d;
    }
}
