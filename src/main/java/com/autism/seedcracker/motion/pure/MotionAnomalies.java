package com.autism.seedcracker.motion.pure;

import java.util.ArrayList;
import java.util.List;

/**
 * Spots the movement problems players notice (pure, unit tested): camera flicks, spinning in
 * place, and twitchy keys flipping back and forth. Feed one sample per tick; read the anomalies.
 */
public final class MotionAnomalies {
    /** A single-tick view change this big is a flick (the eased aim tops out around 25 deg/tick). */
    public static final float FLICK_DEG = 35f;
    /** Turned this much in total over the window while barely moving = spinning. */
    public static final float SPIN_DEG = 300f;
    public static final double SPIN_MAX_MOVE = 1.5;
    /** Movement keys changing this many times in the window = twitchy input. */
    public static final int KEY_FLIPS = 8;
    public static final int WINDOW = 40;

    private final float[] yawDelta = new float[WINDOW];
    private final double[] x = new double[WINDOW], z = new double[WINDOW];
    private final int[] keys = new int[WINDOW];
    private int n, head;
    private float lastYaw;
    private boolean hasLast;
    private int spinCooldown, flipCooldown;

    /** {@code keyMask}: bit 0 fwd, 1 back, 2 left, 3 right, 4 jump. */
    public List<String> sample(float yaw, double px, double pz, int keyMask) {
        List<String> out = new ArrayList<>(0);
        float d = hasLast ? RotationMath.wrap(yaw - lastYaw) : 0f;
        lastYaw = yaw;
        hasLast = true;
        head = (head + 1) % WINDOW;
        yawDelta[head] = d;
        x[head] = px;
        z[head] = pz;
        keys[head] = keyMask;
        if (n < WINDOW) n++;
        if (Math.abs(d) >= FLICK_DEG) out.add(String.format(java.util.Locale.ROOT, "FLICK %.0f deg in one tick", d));
        if (spinCooldown > 0) spinCooldown--;
        if (flipCooldown > 0) flipCooldown--;
        if (n < WINDOW) return out;
        int oldest = (head + 1) % WINDOW;
        // Distance walked, not displacement: a full loop ends where it started but did move.
        double moved = 0;
        for (int i = 1; i < WINDOW; i++) {
            int a = (oldest + i - 1) % WINDOW, b = (oldest + i) % WINDOW;
            moved += Math.hypot(x[b] - x[a], z[b] - z[a]);
        }
        float turned = 0;
        for (float v : yawDelta) turned += Math.abs(v);
        if (spinCooldown == 0 && turned >= SPIN_DEG && moved <= SPIN_MAX_MOVE) {
            out.add(String.format(java.util.Locale.ROOT, "SPIN turned %.0f deg in 2s, moved only %.1f blocks", turned, moved));
            spinCooldown = WINDOW;
        }
        int flips = 0;
        for (int i = 1; i < WINDOW; i++) {
            int a = keys[(oldest + i - 1) % WINDOW] & 0x0F, b = keys[(oldest + i) % WINDOW] & 0x0F;
            if (a != b) flips++;
        }
        if (flipCooldown == 0 && flips >= KEY_FLIPS) {
            out.add("TWITCHY keys changed " + flips + " times in 2s");
            flipCooldown = WINDOW;
        }
        return out;
    }

    public void reset() {
        n = 0;
        hasLast = false;
        spinCooldown = flipCooldown = 0;
    }
}
