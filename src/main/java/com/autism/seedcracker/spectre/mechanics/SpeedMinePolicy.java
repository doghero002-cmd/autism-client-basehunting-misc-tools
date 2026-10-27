package com.autism.seedcracker.spectre.mechanics;

/** SPDX-License-Identifier: GPL-3.0-only. Spectre SpeedMine rules, d5a7dd3. */
public final class SpeedMinePolicy {
    private SpeedMinePolicy() {}

    public static boolean affects(boolean air, boolean spawner, boolean gravel, boolean shovel, boolean axe) {
        return !air && !spawner && (gravel || (!shovel && !axe));
    }

    public static float scale(boolean affected, float progress, double breakAt) {
        if (!affected || !Float.isFinite(progress) || progress <= 0.0f) return progress;
        // Malformed settings must never turn into infinite or instant breaking.
        double threshold = Double.isFinite(breakAt) ? Math.clamp(breakAt, 0.3, 1.0) : 1.0;
        return (float) (progress / threshold);
    }

    public static boolean dropsDelay(boolean affected, float progress, boolean skip, boolean keepInstant) {
        return affected && skip && Float.isFinite(progress) && progress > 0.0f
            && (!keepInstant || progress < 1.0f);
    }
}
