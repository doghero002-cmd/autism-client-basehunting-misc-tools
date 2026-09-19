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
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
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
     * Clicks the confirm/accept button on DonutSMP's custom tpahere screen using the AUTISM
     * Client's macro custom-menu system: it captures the open screen as a snapshot, picks the
     * green/accept button, and submits it (the same path a "custom screen" macro action uses).
     * Falls back to invoking the first clickable widget directly if the macro path finds no
     * accept button. Returns true if a button was activated.
     */
    private boolean clickConfirm(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen == null) return false;
        boolean clicked = doClickConfirm(mc);
        if (clicked) {
            // We just accepted a teleport: grace the setback detector for the incoming teleport.
            com.autism.seedcracker.modules.FlagDetectorModule.grace(5000L);
        }
        return clicked;
    }

    private boolean doClickConfirm(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen == null) return false;

        // Primary: the macro custom-menu path (snapshot -> accept button -> submit).
        try {
            autismclient.api.custommenu.CustomMenuSnapshot snapshot =
                autismclient.util.custommenu.CustomMenuScreens.openScreenSnapshot(mc);
            if (snapshot != null) {
                autismclient.api.custommenu.CustomMenuButton accept =
                    autismclient.util.macro.CustomMenuActionSupport.acceptButton(snapshot.buttons());
                if (accept != null) {
                    var result = autismclient.api.custommenu.CustomMenuAdapterRegistry.submit(
                        snapshot, new autismclient.api.custommenu.CustomMenuSubmission(java.util.Map.of(), accept));
                    if (result != null && result.success()) return true;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the direct-click fallback below.
        }

        // Fallback: invoke the best-matching / first clickable widget directly.
        String needle = confirmText.get().trim().toLowerCase(java.util.Locale.ROOT);
        AbstractWidget match = null;
        AbstractWidget fallback = null;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            String msg = widget.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
            if (!needle.isEmpty() && msg.contains(needle)) { match = widget; break; }
            if (msg.contains("confirm") || msg.contains("accept") || msg.contains("yes")
                || msg.contains("tp") || msg.contains("teleport")) { if (match == null) match = widget; }
            if (fallback == null && widget.active && widget.visible) fallback = widget;
        }
        AbstractWidget target = match != null ? match : fallback;
        if (target == null) return false;
        double cx = target.getX() + target.getWidth() / 2.0;
        double cy = target.getY() + target.getHeight() / 2.0;
        target.onClick(new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0)), false);
        return true;
    }
}
