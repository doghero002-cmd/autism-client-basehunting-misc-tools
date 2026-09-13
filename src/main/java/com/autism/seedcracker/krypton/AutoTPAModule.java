package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto TPA.
 *
 * Reproduces the DonutSMP /tpahere macro: send the teleport command, wait for the custom
 * confirmation screen to open, click its "Confirm" button, wait, then repeat. The previous
 * version only sent the command and never confirmed the screen, so the teleport never fired.
 *
 * Flow (mirrors the recorded macro):
 *   chat /tpahere|tpa <player>  ->  wait (send delay)  ->  click "Confirm" on the custom
 *   screen  ->  wait (confirm delay)  ->  loop.
 */
public final class AutoTPAModule extends Module {

    public enum Mode { TPA, TPAHERE }

    private enum Stage { SEND, WAIT_CONFIRM_SCREEN, CLICK_CONFIRM, WAIT_LOOP }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.TPAHERE, Mode.values())
        .description("TPA = teleport to them. TPAHERE = teleport them to you.")
        .group("General"));
    private final StringSetting player = add(new StringSetting("player", "Player", "")
        .description("Target player name.").group("General"));
    private final IntSetting sendDelayMs = add(new IntSetting(
            "send-delay", "Send delay (ms)", 150, 0, 5000, 10)
        .description("Delay after sending the command, before clicking Confirm.")
        .group("Timing"));
    private final IntSetting confirmDelayMs = add(new IntSetting(
            "confirm-delay", "Confirm delay (ms)", 150, 0, 5000, 10)
        .description("Delay after clicking Confirm, before the next teleport.")
        .group("Timing"));
    private final IntSetting screenTimeoutMs = add(new IntSetting(
            "screen-timeout", "Screen timeout (ms)", 3000, 500, 15000, 100)
        .description("How long to wait for the confirm screen before retrying the command.")
        .group("Timing"));
    private final StringSetting confirmText = add(new StringSetting(
            "confirm-text", "Confirm button text", "Confirm")
        .description("Text on the button to click (case-insensitive substring).")
        .group("General"));

    private Stage stage = Stage.SEND;
    private long stageSince = 0L;

    public AutoTPAModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-tpa", "Auto TPA", category,
            "Spam /tpa or /tpahere and click the confirm screen, on configurable delays.");
    }

    @Override
    public void onEnable() {
        stage = Stage.SEND;
        stageSince = 0L;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (player.get().trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        switch (stage) {
            case SEND -> {
                String cmd = (mode.get() == Mode.TPA ? "tpa " : "tpahere ") + player.get().trim();
                mc.getConnection().sendCommand(cmd);
                stage = Stage.WAIT_CONFIRM_SCREEN;
                stageSince = now;
            }
            case WAIT_CONFIRM_SCREEN -> {
                if (now - stageSince < sendDelayMs.get()) return;
                if (clickConfirm(mc)) {
                    stage = Stage.CLICK_CONFIRM;
                    stageSince = now;
                } else if (now - stageSince > screenTimeoutMs.get()) {
                    // No screen appeared in time: re-send the command.
                    stage = Stage.SEND;
                }
            }
            case CLICK_CONFIRM -> {
                // Brief settle after the click before looping.
                stage = Stage.WAIT_LOOP;
                stageSince = now;
            }
            case WAIT_LOOP -> {
                int jitter = ThreadLocalRandom.current().nextInt(0, 60);
                if (now - stageSince >= confirmDelayMs.get() + jitter) {
                    stage = Stage.SEND;
                }
            }
        }
    }

    /**
     * Finds a button whose text contains the configured confirm text on the currently open
     * screen and clicks it. Returns true if a button was clicked.
     */
    private boolean clickConfirm(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen == null) return false;
        String needle = confirmText.get().trim().toLowerCase(java.util.Locale.ROOT);
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                String msg = widget.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
                if (msg.contains(needle)) {
                    double cx = widget.getX() + widget.getWidth() / 2.0;
                    double cy = widget.getY() + 10.0;
                    widget.mouseClicked(new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0)), false);
                    return true;
                }
            }
        }
        return false;
    }
}
