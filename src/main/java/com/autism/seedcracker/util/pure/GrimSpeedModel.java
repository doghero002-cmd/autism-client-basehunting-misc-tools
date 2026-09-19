package com.autism.seedcracker.util.pure;

/**
 * Grim Simulation(SpeedA) threshold + buffer model (pure - unit tested).
 *
 * Mirrors Apex azheng Emulator / Grim's actual speed check: a per-move-packet XZ delta above the
 * situational threshold increments a buffer (+1), below decrements (-1, floored at 0); Grim flags
 * above 13. We warn earlier so automation can brake first. All world-state inputs are passed as
 * booleans/ints so the model has no Minecraft dependency.
 */
public final class GrimSpeedModel {

    private GrimSpeedModel() {}

    /** Situational max XZ speed per tick, mirroring Grim's modifiers. */
    public static double threshold(boolean onGround, int speedEffectLevel, int groundTicks,
                                   boolean slimeTicksActive, boolean onStairSlab,
                                   boolean iceActive, boolean underBlockActiveAndJumping,
                                   float baseGround, float baseAir) {
        double threshold = onGround ? baseGround : baseAir;
        if (slimeTicksActive) threshold += 0.07f;
        threshold += (groundTicks < 5) ? speedEffectLevel * 0.06f : speedEffectLevel * 0.046f;
        if (onStairSlab) threshold *= 1.8f;
        if (iceActive && groundTicks < 5) threshold *= 1.7f;
        if (underBlockActiveAndJumping) threshold *= 2.0f;
        return threshold;
    }

    /** Buffer update: +1 over threshold, -1 under (floored at 0). */
    public static int updateBuffer(int buffer, double deltaXZ, double threshold) {
        return deltaXZ > threshold ? buffer + 1 : Math.max(0, buffer - 1);
    }

    /** Decaying tick-state counter used for ice/slime/under-block (bounded 0..60). */
    public static int updateTickState(int current, boolean active, int gain) {
        return Math.max(0, active ? Math.min(60, current + gain) : current - 1);
    }
}
