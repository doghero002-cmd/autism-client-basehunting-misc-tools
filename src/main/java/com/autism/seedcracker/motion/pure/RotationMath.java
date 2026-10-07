package com.autism.seedcracker.motion.pure;

/**
 * Human-like aim easing (pure, unit tested). Both axes travel together along one angular vector so
 * the crosshair moves in a near-straight line; speed ramps up with {@code accel}, is capped at
 * {@code maxSpeed}, and is limited near the target by sqrt(2*accel*distance) so it brakes instead
 * of overshooting. A little perpendicular jitter keeps the line from being ruler-straight.
 */
public final class RotationMath {
    private RotationMath() {}

    public record Profile(float maxSpeed, float accel, float jitter, float convergeDeg,
                          boolean smooth, boolean humanize) {
        public Profile(float maxSpeed, float accel, float jitter, float convergeDeg) {
            this(maxSpeed, accel, jitter, convergeDeg, true, true);
        }

        public static Profile defaults() {
            return new Profile(22f, 4.5f, 0.12f, 0.6f);
        }

        /** Slower and gentler; strict anti-cheats look at peak angular speed. */
        public static Profile careful() {
            return new Profile(12f, 2.5f, 0.10f, 0.6f);
        }

        /** Instant (no easing) — fastest reactions, most bot-like. */
        public static Profile instant() {
            return new Profile(1000f, 1000f, 0f, 0.6f, false, false);
        }
    }

    /** Per-owner velocity carried between ticks. */
    public static final class State {
        float velocity;

        public void reset() {
            velocity = 0f;
        }
    }

    public record Step(float yaw, float pitch, boolean converged) {}

    /** Wraps to [-180, 180). */
    public static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** {yaw, pitch} in Minecraft convention (yaw 0 = +Z/south, 90 = -X/west; pitch up is negative). */
    public static float[] lookAt(double dx, double dy, double dz) {
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{wrap(yaw), clampPitch(pitch)};
    }

    public static float clampPitch(float p) {
        return Math.max(-90f, Math.min(90f, p));
    }

    /** Angular distance between two views (yaw wrapped). */
    public static float distance(float yawA, float pitchA, float yawB, float pitchB) {
        float dy = wrap(yawB - yawA);
        float dp = pitchB - pitchA;
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    /**
     * One tick toward the target. {@code r1}/{@code r2} are uniform [0,1) randoms (injected so
     * tests are deterministic).
     */
    public static Step step(float yaw, float pitch, float targetYaw, float targetPitch,
                            Profile p, State s, double r1, double r2) {
        targetPitch = clampPitch(targetPitch);
        float dYaw = wrap(targetYaw - yaw);
        float dPitch = targetPitch - pitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (dist <= p.convergeDeg() || !p.smooth()) {
            s.velocity = 0f;
            return new Step(wrap(yaw + dYaw), targetPitch, true);
        }
        float brake = (float) Math.sqrt(2.0 * p.accel() * dist);
        float v = Math.min(Math.min(s.velocity + p.accel(), p.maxSpeed()), brake);
        v *= (float) (1.0 - p.jitter() * 0.5 + p.jitter() * r1);
        // Humanize: rare micro-hesitations on big turns so the yaw curve isn't a metronome (PvP-aim trick).
        if (p.humanize() && dist > 8f && r2 < 0.04) v *= 0.25f;
        v = Math.min(v, dist);
        s.velocity = v;

        float ux = dYaw / dist, uy = dPitch / dist;
        // Perpendicular wobble shrinks to nothing as we land, so precise final aim is unaffected.
        float wobble = (float) ((r2 - 0.5) * p.jitter() * v * Math.min(1f, dist / 10f));
        // Wrap the eased yaw so it never drifts past +-180 (an unwrapped yaw made turns take the long way round
        // and filled the trace with values like 425 deg).
        float nYaw = wrap(yaw + ux * v - uy * wobble);
        float nPitch = clampPitch(pitch + uy * v + ux * wobble);
        return new Step(nYaw, nPitch, false);
    }
}
