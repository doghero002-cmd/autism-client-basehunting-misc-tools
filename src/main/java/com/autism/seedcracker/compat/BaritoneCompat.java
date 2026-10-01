package com.autism.seedcracker.compat;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;

/**
 * Obfuscation-proof Baritone bridge. The client's AutismCompatManager (isBaritoneAvailable /
 * sendBaritoneCommand / startBaritoneGoTo / stopBaritone / isBaritoneBusy) was renamed in later
 * (obfuscated) builds, so we can't reference it at compile time. This shim detects Baritone via
 * reflection at runtime and drives it through the public Baritone API surface (the #command chat
 * prefix, which is stable across Baritone forks), degrading to no-ops when Baritone isn't loaded.
 *
 * If Baritone isn't present every method safely no-ops / reports unavailable, so the base-finding
 * modules that lean on it simply idle instead of crashing.
 */
public final class BaritoneCompat {
    private BaritoneCompat() {}

    private static volatile Boolean available = null;
    private static Method sendCommandMethod = null;   // BaritoneCompat.sendBaritoneCommand or BaritoneAPI equivalent
    private static boolean probed = false;

    /** True if a Baritone integration is reachable (was BaritoneCompat.isBaritoneAvailable). */
    public static boolean isBaritoneAvailable() {
        probe();
        return available != null && available;
    }

    /** Send a Baritone chat command (e.g. "#set freeLook true"). No-op if unavailable. */
    public static void sendBaritoneCommand(Minecraft mc, String command) {
        if (!isBaritoneAvailable() || mc == null || mc.getConnection() == null) return;
        try {
            // Baritone reads its own command prefix from chat; sending the raw "#..." line through
            // the normal chat channel is the stable cross-fork control path.
            mc.getConnection().sendChat(command);
        } catch (Throwable ignored) {}
    }

    /** Path Baritone toward a block position (was startBaritoneGoTo). Uses the #goto command. */
    public static boolean startBaritoneGoTo(Minecraft mc, int x, int y, int z) {
        if (!isBaritoneAvailable()) return false;
        sendBaritoneCommand(mc, "#goto " + x + " " + y + " " + z);
        return true;
    }

    /** Stop Baritone's current task (was stopBaritone). */
    public static void stopBaritone(Minecraft mc) {
        if (!isBaritoneAvailable()) return;
        sendBaritoneCommand(mc, "#stop");
    }

    /** True while Baritone has an active process (was isBaritoneBusy). Conservative: we can't read
     * its internal process state without the renamed helper, so we report not-busy (lets callers
     * re-issue goals) rather than falsely claiming busy and stalling them. */
    public static boolean isBaritoneBusy() {
        return false;
    }

    /** One-time reflective probe for a Baritone presence. Caches the result. */
    private static synchronized void probe() {
        if (probed) return;
        probed = true;
        boolean found = false;
        try {
            // BaritoneAPI's settings/provider classes are the stable markers across forks.
            Class.forName("baritone.api.BaritoneAPI", false,
                Minecraft.class.getClassLoader());
            found = true;
        } catch (Throwable t1) {
            try {
                Class.forName("baritone.Baritone", false, Minecraft.class.getClassLoader());
                found = true;
            } catch (Throwable t2) {
                found = false;
            }
        }
        available = found;
        sendCommandMethod = null; // chat-prefix path needs no reflected method
    }
}
