package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Mouse-delta rotation engine (the most legit way to rotate).
 *
 * Port of the wocky-client (lol.ethane) mouse-rotation pipeline to Mojang 26.2:
 *  - a rotation model produces a desired yaw/pitch (time-eased, jittered, human-looking), then
 *  - the delta from the player's current view is converted into INTEGER mouse-cursor deltas via
 *    {@code cursorDelta = (angle / sensitivityMultiplier) / 0.15} and rounded,
 *  - a MouseHandler mixin injects those deltas into the REAL turnPlayer path, so the resulting
 *    rotation enters the exact code path a hardware mouse uses. Grim/GCD rotation checks treat it
 *    as a valid mouse movement because it literally went through the mouse handler.
 *
 * Usage: {@link #rotateTo(float, float)} sets a target; the mixin pulls {@link #nextCursorDeltaX()}
 * / {@link #nextCursorDeltaY()} each frame. {@link #isActive()} reports whether a rotation is in
 * flight. {@link #stop()} cancels.
 */
public final class MouseRotation {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final MouseRotation INSTANCE = new MouseRotation();

    // Reuse the legit human-mouse model for the underlying target tracking.
    private final LegitMovement model = new LegitMovement();

    private volatile boolean active = false;
    private float targetYaw;
    private float targetPitch;
    private boolean initialized = false;
    // Prefer the BASE CLIENT's own mouse-input simulator (AutismMouseInputSimulator + its
    // AutismMouseHandlerMixin): one proven injection point instead of two mixins fighting over
    // turnPlayer. Our own mixin stays as the fallback if the simulator is unavailable.
    private boolean simulatorAvailable = true;

    private MouseRotation() {}

    public static MouseRotation get() { return INSTANCE; }

    /** Begin rotating the view to the given yaw/pitch through the real mouse path. */
    public void rotateTo(float yaw, float pitch) {
        if (!initialized) { model.reset(); initialized = true; }
        targetYaw = yaw;
        targetPitch = Mth.clamp(pitch, -90f, 90f);
        active = true;
        if (simulatorAvailable && mc.player != null) {
            try {
                if (!autismclient.util.AutismMouseInputSimulator.canUseMouseLook()) return;
                if (!isActive()) return; // already on target - queue nothing
                // One human-model step per call, queued as a mouse delta through the client's
                // own simulator (it converts to integer cursor counts internally).
                float[] rot = model.update(targetYaw, targetPitch);
                float dYaw = angleDiff(mc.player.getYRot(), rot[0]);
                float dPitch = rot[1] - mc.player.getXRot();
                autismclient.util.AutismMouseInputSimulator.queueRotationDelta(dYaw, dPitch);
            } catch (Throwable t) {
                simulatorAvailable = false; // old client build: fall back to our own mixin
                com.autism.seedcracker.util.FlagLog.flag("INFO", "MouseRotation",
                    "AutismMouseInputSimulator unavailable, using own mixin: " + t);
            }
        }
    }

    /** Stop driving the mouse rotation. */
    public void stop() {
        active = false;
        if (simulatorAvailable) {
            try { autismclient.util.AutismMouseInputSimulator.clear(); } catch (Throwable ignored) {}
        }
    }

    public boolean isActive() {
        if (!active || mc.player == null) return false;
        // Done once we're within a cursor step of the target.
        return Math.abs(angleDiff(mc.player.getYRot(), targetYaw)) > 0.6f
            || Math.abs(mc.player.getXRot() - targetPitch) > 0.6f;
    }

    /** The integer cursor X delta for this frame (0 when inactive or the simulator owns input). */
    public double nextCursorDeltaX() {
        if (simulatorAvailable || !isActive() || mc.player == null) return 0.0;
        float[] rot = model.update(targetYaw, targetPitch);
        double yawDelta = angleDiff(mc.player.getYRot(), rot[0]);
        return Math.round(cursorDelta(yawDelta, sensitivity()));
    }

    /** The integer cursor Y delta for this frame (0 when inactive or the simulator owns input), honouring invert-Y. */
    public double nextCursorDeltaY() {
        if (simulatorAvailable || !isActive() || mc.player == null) return 0.0;
        float[] rot = model.update(targetYaw, targetPitch);
        double pitchDelta = rot[1] - mc.player.getXRot();
        double d = Math.round(cursorDelta(pitchDelta, sensitivity()));
        try {
            if (mc.options.invertMouseY().get()) d = -d;
        } catch (Throwable ignored) {}
        return d;
    }

    /** wocky RotationUtil.getCursorDelta: convert an angle to raw cursor steps. */
    private static double cursorDelta(double angleDeg, double sensitivityMultiplier) {
        return (angleDeg / Math.max(1.0e-6, sensitivityMultiplier)) / 0.15;
    }

    /** The sensitivity multiplier Minecraft applies inside turnPlayer: (sens*0.6+0.2)^3 * 8. */
    private static double sensitivity() {
        double s = 0.5;
        try { s = mc.options.sensitivity().get(); } catch (Throwable ignored) {}
        double f = s * 0.6000000238418579 + 0.20000000298023224;
        return f * f * f * 8.0;
    }

    private static float angleDiff(float from, float to) {
        float d = (to - from) % 360.0f;
        if (d > 180.0f) d -= 360.0f;
        else if (d < -180.0f) d += 360.0f;
        return d;
    }
}
