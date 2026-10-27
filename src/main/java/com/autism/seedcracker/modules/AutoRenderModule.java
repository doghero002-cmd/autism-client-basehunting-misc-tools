package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.RenderDistanceCycle;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.RelogPersistence;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;

/**
 * Auto Render. Existing class, module ID and settings retained for addon callers/profiles.
 * Render-distance mechanics adapted from Spectre RenderDistanceToggleModule (GPL-3.0-only),
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, by cody-raves and contributors.
 * Unlike the previous Zelith-derived simulation-distance cycle, this changes render distance.
 * Deviations: keep the existing configurable hold duration; restore only still-owned values.
 */
public final class AutoRenderModule extends Module {
    private static final System.Logger LOGGER = System.getLogger(AutoRenderModule.class.getName());

    private final BoolSetting notification = add(new BoolSetting("notification", "Notification", true)
        .description("Notify when the temporary render-distance cycle triggers.")
        .group("General"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Notification sound", true)
        .description("Play a sound with the notification.")
        .group("General"));
    private final IntSetting holdTicks = add(new IntSetting("hold-ticks", "Boost hold (ticks)", 20, 5, 200, 5)
        .description("Ticks before restoring the previous render distance, if still owned.")
        .group("General"));
    private final IntSetting lowDistance = add(new IntSetting("low-distance", "Low distance", 2, 2, 32, 1)
        .description("Temporary render distance in chunks. Simulation distance is never changed.")
        .group("General"));
    private final IntSetting resetY = add(new IntSetting("reset-y", "Reset Y", -1, -64, 320, 1)
        .description("Cycle at or below this block Y; rearm above Y + 2.")
        .group("General"));

    private final RenderDistanceCycle cycle = new RenderDistanceCycle();
    private ClientLevel seenLevel;
    private LocalPlayer seenPlayer;

    public AutoRenderModule() {
        super(SeedcrackerAddon.ID + ":z-auto-render", "Auto Render",
            "Temporarily cycles render distance below a Y level, then restores only its own change.");
    }

    @Override
    public void onEnable() {
        clearSession();
    }

    @Override
    public void onDisable() {
        clearSession();
    }

    @Override
    public void onGameLeft() {
        // Persistence does not imply ownership of an option across worlds.
        clearSession();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void onGameJoin() {
        clearSession();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled()) return;
        if (mc.player == null || mc.level == null || mc.options == null) {
            clearSession();
            return;
        }
        if (seenLevel != mc.level || seenPlayer != mc.player) {
            boolean changingWorld = seenLevel != null;
            clearSession();
            seenLevel = mc.level;
            seenPlayer = mc.player;
            if (changingWorld) return;
        }

        try {
            boolean wasCycling = cycle.ownsDistance();
            var change = cycle.tick(mc.options.renderDistance().get(), mc.player.getBlockY(),
                resetY.get(), lowDistance.get(), holdTicks.get(), ActionPacer::tryAction);
            if (change.isEmpty()) return;
            apply(mc, change.getAsInt());
            if (!wasCycling && cycle.ownsDistance() && notification.get()) {
                AutismClientMessaging.sendPrefixed("§7Auto Render: cycling render distance to reload chunks.");
                if (sound.get()) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Auto Render option update failed", failure);
            AutismClientMessaging.sendPrefixed("§cAuto Render stopped: render-distance update failed.");
            setEnabledSilently(false);
        }
    }

    private void clearSession() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.options != null) {
                var restore = cycle.release(mc.options.renderDistance().get());
                // Cleanup cannot be delayed by the shared pacer.
                if (restore.isPresent()) apply(mc, restore.getAsInt());
            }
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Auto Render could not restore its option", failure);
        } finally {
            cycle.reset();
            seenLevel = null;
            seenPlayer = null;
        }
    }

    private static void apply(Minecraft mc, int distance) {
        mc.options.renderDistance().set(distance);
        mc.options.broadcastOptions();
    }
}
