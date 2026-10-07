package com.autism.seedcracker.util.pure;

/**
 * Fishing-bobber bite detection (pure, unit tested).
 *
 * A bite is the sharp downward jerk the server applies to the hook: current dy well below the
 * float band after a tick that was inside it, while the bobber is horizontally settled (casting
 * flight and current-drift both move horizontally and must not trigger).
 */
public final class BiteDetect {
    private BiteDetect() {}

    /** Sharp-drop threshold (vanilla applies about -0.1..-0.2 on a bite). */
    public static final double BITE_DY = -0.07;
    /** Floating band: the bobber bobs gently within this. */
    public static final double FLOAT_DY = -0.03;
    /** Settled = horizontal speed squared below this (still flying/casting otherwise). */
    public static final double SETTLED_H2 = 0.001;

    public static boolean isBite(double dy, double lastDy, double horizontalSq) {
        return dy < BITE_DY && lastDy >= FLOAT_DY && horizontalSq < SETTLED_H2;
    }
}
