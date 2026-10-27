package com.autism.seedcracker.spectre.mechanics;

/** SPDX-License-Identifier: GPL-3.0-only. Spectre TridentUtil minimum-charge calculation, d5a7dd3. */
public final class TridentChargePolicy {
    private TridentChargePolicy() {}

    public static int minimum(int vanilla, double scale) {
        if (vanilla <= 0 || !Double.isFinite(scale)) return vanilla;
        return Math.max(1, (int) Math.round(vanilla * Math.clamp(scale, 0.0, 1.0)));
    }

    /** Riptide's repeated manual use retains Spectre's nine-tick floor, independent of TridentUtil. */
    public static int repeatMinimum(int tridentMinimum) { return Math.max(9, tridentMinimum); }
}
