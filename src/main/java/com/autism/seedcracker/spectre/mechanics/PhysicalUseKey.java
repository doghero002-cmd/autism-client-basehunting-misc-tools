package com.autism.seedcracker.spectre.mechanics;

import com.autism.seedcracker.util.FlagLog;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Reads the configured physical use binding, not a key state forced by another automation. */
public final class PhysicalUseKey {
    private String binding;
    private InputConstants.Key key;
    private int keyboardCode = GLFW.GLFW_KEY_UNKNOWN;

    public boolean held(Minecraft mc) {
        if (!mc.isSameThread() || !mc.isWindowActive() || mc.gui.screen() != null) return false;
        String current = mc.options.keyUse.saveString();
        if (!current.equals(binding)) resolve(current);
        if (key == null || key.getValue() < 0) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return keyboardCode >= 0 && InputConstants.isKeyDown(mc.getWindow(), keyboardCode);
    }

    private void resolve(String current) {
        binding = current;
        key = null;
        keyboardCode = GLFW.GLFW_KEY_UNKNOWN;
        try {
            key = InputConstants.getKey(current);
        } catch (IllegalArgumentException error) {
            FlagLog.warn("INPUT", "Riptide", "Cannot read configured use binding: " + current + " (" + error + ")");
            return;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            keyboardCode = key.getValue();
        } else if (key.getType() == InputConstants.Type.SCANCODE && key.getValue() >= 0) {
            // Resolve a scan-code binding once, not a full keyboard scan on every tick.
            for (int candidate = GLFW.GLFW_KEY_SPACE; candidate <= GLFW.GLFW_KEY_LAST; candidate++) {
                if (pollableGlfwKey(candidate) && GLFW.glfwGetKeyScancode(candidate) == key.getValue()) {
                    keyboardCode = candidate;
                    break;
                }
            }
            if (keyboardCode < 0) FlagLog.warn("INPUT", "Riptide", "No GLFW key for use binding: " + current);
        }
    }

    // GLFW key tokens are sparse. Querying the gaps produces GLFW_INVALID_ENUM errors.
    static boolean pollableGlfwKey(int code) {
        return code == GLFW.GLFW_KEY_SPACE || code == GLFW.GLFW_KEY_APOSTROPHE
            || code >= GLFW.GLFW_KEY_COMMA && code <= GLFW.GLFW_KEY_9
            || code == GLFW.GLFW_KEY_SEMICOLON || code == GLFW.GLFW_KEY_EQUAL
            || code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_RIGHT_BRACKET
            || code == GLFW.GLFW_KEY_GRAVE_ACCENT
            || code >= GLFW.GLFW_KEY_WORLD_1 && code <= GLFW.GLFW_KEY_WORLD_2
            || code >= GLFW.GLFW_KEY_ESCAPE && code <= GLFW.GLFW_KEY_END
            || code >= GLFW.GLFW_KEY_CAPS_LOCK && code <= GLFW.GLFW_KEY_PAUSE
            || code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25
            || code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_EQUAL
            || code >= GLFW.GLFW_KEY_LEFT_SHIFT && code <= GLFW.GLFW_KEY_MENU;
    }

    public void reset() {
        binding = null;
        key = null;
        keyboardCode = GLFW.GLFW_KEY_UNKNOWN;
    }
}
