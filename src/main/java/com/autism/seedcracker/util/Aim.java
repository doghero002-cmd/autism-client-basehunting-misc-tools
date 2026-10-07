package com.autism.seedcracker.util;

import java.util.concurrent.ThreadLocalRandom;

import com.autism.seedcracker.util.pure.GcdMath;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Shared aim surface for every module that writes player rotations.
 *
 * All server-visible rotations should flow through here so the whole addon presents one
 * consistent rotation fingerprint:
 *  - {@link #set} applies a rotation whose DELTA from the current view is an integer multiple
 *    of the mouse-sensitivity GCD step (Grim's rotation check), with sub-step remainders
 *    carried forward like vanilla's turnPlayer.
 *  - {@link #ease} walks toward a target over multiple ticks with a capped, jittered per-tick
 *    step - the anti-flick path for modules that previously snapped with setYRot/setXRot.
 */
public final class Aim {
    private Aim() {}

    /** Max per-tick turn speed (deg) for {@link #ease}; ~human fast mouse sweep. */
    private static final float EASE_MAX_STEP_DEG = 25.0f;
    /** Fraction of the remaining distance covered per tick (exponential approach). */
    private static final float EASE_APPROACH = 0.45f;

    // GCD remainder carry, per axis (main-thread only like all rotation writes).
    private static double yawRemainder;
    private static double pitchRemainder;

    /** Reset remainder carry (call on world change / module disable if aim owned the view). */
    public static void reset() {
        yawRemainder = 0.0;
        pitchRemainder = 0.0;
    }

    /**
     * Set the rotation now, GCD-quantized against the current view. Use for final/landing sets
     * where the module has already smoothed its approach.
     */
    public static void set(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        double step = gcdStep(mc);
        GcdMath.Quantized qy = GcdMath.quantizeDelta(mc.player.getYRot(), yaw, yawRemainder,
            step, Tuning.GCD_REMAINDER_CLAMP, true);
        GcdMath.Quantized qp = GcdMath.quantizeDelta(mc.player.getXRot(),
            Mth.clamp(pitch, -90f, 90f), pitchRemainder, step, Tuning.GCD_REMAINDER_CLAMP, false);
        yawRemainder = qy.remainder();
        pitchRemainder = qp.remainder();
        mc.player.setYRot(qy.angle());
        mc.player.setXRot(Mth.clamp(qp.angle(), -90f, 90f));
    }

    /**
     * Ease one tick toward the target. Returns true once the view is within
     * {@link Tuning#AIM_CONVERGENCE_DEG} of the target (safe to interact).
     */
    public static boolean ease(Minecraft mc, float targetYaw, float targetPitch) {
        if (mc.player == null) return false;
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();
        float dYaw = wrapDeg(targetYaw - curYaw);
        float dPitch = Mth.clamp(targetPitch, -90f, 90f) - curPitch;

        if (Math.abs(dYaw) <= Tuning.AIM_CONVERGENCE_DEG
            && Math.abs(dPitch) <= Tuning.AIM_CONVERGENCE_DEG) {
            set(mc, targetYaw, targetPitch);
            return true;
        }

        // Exponential approach with a hard cap and ±12% human speed jitter.
        float jitter = 0.88f + ThreadLocalRandom.current().nextFloat() * 0.24f;
        float stepYaw = Mth.clamp(dYaw * EASE_APPROACH * jitter, -EASE_MAX_STEP_DEG, EASE_MAX_STEP_DEG);
        float stepPitch = Mth.clamp(dPitch * EASE_APPROACH * jitter, -EASE_MAX_STEP_DEG, EASE_MAX_STEP_DEG);
        set(mc, curYaw + stepYaw, curPitch + stepPitch);
        return false;
    }

    /** Whether the current view is already converged on the target (no write). */
    public static boolean converged(Minecraft mc, float targetYaw, float targetPitch) {
        if (mc.player == null) return false;
        return Math.abs(wrapDeg(targetYaw - mc.player.getYRot())) <= Tuning.AIM_CONVERGENCE_DEG
            && Math.abs(Mth.clamp(targetPitch, -90f, 90f) - mc.player.getXRot()) <= Tuning.AIM_CONVERGENCE_DEG;
    }

    private static double gcdStep(Minecraft mc) {
        double sens = mc.options != null ? mc.options.sensitivity().get() : 0.5;
        return GcdMath.step(sens);
    }

    private static float wrapDeg(float deg) {
        return (float) (((deg % 360.0) + 540.0) % 360.0 - 180.0);
    }
}
