package com.autism.seedcracker.util;

/**
 * Relog persistence (always on).
 *
 * Modules previously auto-disabled from their {@code onGameLeft} hook; that was made optional and
 * is now permanent behaviour: modules stay enabled across world leaves/relogs. The helper is kept
 * (rather than deleting the 28 call sites) so the behaviour stays a one-line change if ever needed.
 */
public final class RelogPersistence {
    private RelogPersistence() {}

    /** Kept for API compatibility; persistence is always on. */
    public static void setPersistOnRelog(boolean v) { /* always on */ }
    public static boolean persistOnRelog() { return true; }

    /** Always false: modules never auto-disable on game-left. */
    public static boolean shouldDisableOnGameLeft() {
        return false;
    }
}
