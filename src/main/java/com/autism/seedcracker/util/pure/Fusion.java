package com.autism.seedcracker.util.pure;

/**
 * Confidence-fusion math (pure, unit tested).
 *
 * Treats each finder's 0-100 confidence as an independent probability that a chunk is a base
 * and fuses with the noisy-OR rule: fused = 1 - prod(1 - ci). Two weak signals (40% + 40%)
 * fuse to 64%, three strong ones saturate toward 100 - which is exactly the "multiple finders
 * agree" semantics we want.
 */
public final class Fusion {
    private Fusion() {}

    /** Noisy-OR fusion of 0-100 confidences; returns 0-100. */
    public static int fuse(int[] confidences) {
        double notBase = 1.0;
        for (int c : confidences) {
            double ci = Math.max(0, Math.min(100, c)) / 100.0;
            notBase *= 1.0 - ci;
        }
        return (int) Math.round((1.0 - notBase) * 100.0);
    }
}
