package com.autism.seedcracker.util;

import autismclient.util.AutismCompatManager;
import net.minecraft.client.Minecraft;

/**
 * Shared "legit / smooth" Baritone profile for the base-finding modules.
 *
 * Baritone's default behaviour snaps the player's view instantly to each block it mines, which is
 * the classic head-flick that anti-cheats (and anyone watching) flag. This helper pushes a set of
 * Baritone settings that make its movement look and behave more human:
 *
 *  - freeLook = on, so Baritone never forces client-side view rotations for pathing.
 *  - antiCheatCompatibility = on.
 *  - blockReachDistance clamped to ~4.5 (vanilla-ish) so it doesn't aim at out-of-reach blocks.
 *  - rotation slow-down / randomization (when the fork exposes those settings) to smooth and
 *    humanise the few rotations it still does.
 *  - path/goal rendering off so nothing visually betrays the bot.
 *
 * Settings that the user's Baritone fork doesn't expose are simply ignored by Baritone (unknown
 * #set names are a no-op), so this is safe across forks.
 */
public final class LegitBaritone {
    private LegitBaritone() {}

    /** Apply the legit profile. Safe to call repeatedly. */
    public static void apply(Minecraft mc) {
        if (!AutismCompatManager.isBaritoneAvailable()) return;

        // Don't force client-side rotations for movement, and be anti-cheat friendly.
        set(mc, "freeLook", true);
        set(mc, "antiCheatCompatibility", true);

        // Vanilla-ish reach so it never aims at blocks a player couldn't reach.
        setF(mc, "blockReachDistance", 4.5);

        // Slow + randomise rotations where the fork supports it (no-ops otherwise).
        setF(mc, "rotationDegreesPerTick", 12.0);
        set(mc, "randomizeRotations", true);
        set(mc, "smoothLook", true);

        // Humanise pacing a little.
        set(mc, "sprintAscends", false);

        // Hide the path / goal / cached-chunk rendering.
        set(mc, "renderPath", false);
        set(mc, "renderGoal", false);
        set(mc, "renderCachedChunks", false);
        set(mc, "renderSelectionBoxes", false);
    }

    private static void set(Minecraft mc, String name, boolean value) {
        AutismCompatManager.sendBaritoneCommand(mc, "#set " + name + " " + value);
    }

    private static void setF(Minecraft mc, String name, double value) {
        AutismCompatManager.sendBaritoneCommand(mc, "#set " + name + " " + value);
    }
}
