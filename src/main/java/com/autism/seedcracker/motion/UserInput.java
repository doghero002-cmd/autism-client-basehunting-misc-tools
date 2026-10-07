package com.autism.seedcracker.motion;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Reads the PHYSICAL movement keys. The bot drives the same KeyMappings, so {@code isDown()} can't
 * tell the player's hands from ours; the raw keyboard state can.
 */
final class UserInput {
    private UserInput() {}

    private static final Map<String, InputConstants.Key> PARSED = new HashMap<>();

    /** True while the player is holding a movement or jump key (game window focused, no menu open). */
    static boolean pressed(Minecraft mc) {
        if (mc.options == null || !mc.isWindowActive() || mc.gui.screen() != null) return false;
        if (freecam(mc)) return false;
        return held(mc, mc.options.keyUp) || held(mc, mc.options.keyDown) || held(mc, mc.options.keyLeft)
            || held(mc, mc.options.keyRight) || held(mc, mc.options.keyJump);
    }

    /** Freecam (any client's): first person but the render camera is detached from the player's eyes. */
    static boolean freecam(Minecraft mc) {
        if (mc.player == null || mc.gameRenderer == null || !mc.options.getCameraType().isFirstPerson()) return false;
        var cam = mc.gameRenderer.mainCamera();
        return cam != null && cam.position().distanceToSqr(mc.player.getEyePosition()) > 4.0;
    }

    private static boolean held(Minecraft mc, KeyMapping km) {
        InputConstants.Key key;
        try {
            key = PARSED.computeIfAbsent(km.saveString(), InputConstants::getKey);
        } catch (IllegalArgumentException e) {
            // Unknown key name (unbound, or from a mod's custom key type): treat as not pressed instead of crashing the tick.
            return false;
        }
        // Mouse-bound movement keys can't be polled this way; ignore them rather than guess.
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() < 0) return false;
        return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }
}
