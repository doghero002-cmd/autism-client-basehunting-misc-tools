package com.autism.seedcracker.util.pure;

/**
 * Mouse-sensitivity GCD math (pure, no Minecraft types - unit tested).
 *
 * A real mouse can only rotate the camera in integer multiples of the per-count step
 * {@code (sens*0.6+0.2)^3 * 8 * 0.15}. Grim's rotation check verifies consecutive rotation
 * DELTAS are such multiples. {@link #quantizeDelta} snaps a desired rotation delta onto that
 * grid, carrying the sub-step remainder forward (AlphaDLC HolyWorldRotation) exactly like the
 * fractional accumulation inside vanilla's turnPlayer.
 */
public final class GcdMath {

    private GcdMath() {}

    /** The per-mouse-count rotation step (degrees) at the given vanilla sensitivity [0..1]. */
    public static double step(double sensitivity) {
        double f = sensitivity * 0.6000000238418579 + 0.20000000298023224;
        return f * f * f * 8.0 * 0.15;
    }

    /** Snap an absolute angle to the GCD grid (legacy behaviour, no remainder). */
    public static float quantize(float angleDeg, double gcdStep) {
        if (gcdStep <= 0) return angleDeg;
        return (float) (Math.round(angleDeg / gcdStep) * gcdStep);
    }

    /**
     * Result of a delta quantization: the new angle and the remainder to carry into the next call.
     */
    public record Quantized(float angle, double remainder) {}

    /**
     * Snap the rotation {@code from -> to} so the applied delta is an integer multiple of
     * {@code gcdStep}, accumulating the leftover into {@code remainder} (clamped to
     * {@code +-remainderClamp*gcdStep} so a slow drift can't bank a huge jump).
     *
     * @param wrapYaw shortest-path wrap across +-180 (true for yaw, false for pitch)
     */
    public static Quantized quantizeDelta(float from, float to, double remainder,
                                          double gcdStep, double remainderClamp, boolean wrapYaw) {
        if (gcdStep <= 0) return new Quantized(to, 0.0);
        double delta = to - from;
        if (wrapYaw) {
            delta = ((delta % 360.0) + 540.0) % 360.0 - 180.0;
        }
        delta += remainder;
        double snapped = Math.round(delta / gcdStep) * gcdStep;
        double rem = delta - snapped;
        double clamp = remainderClamp * gcdStep;
        rem = Math.max(-clamp, Math.min(clamp, rem));
        return new Quantized((float) (from + snapped), rem);
    }
}
