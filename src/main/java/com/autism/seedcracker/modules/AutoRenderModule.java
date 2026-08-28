package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Auto Render.
 *
 * When you drop below Y=-2 it temporarily raises the simulation/render distance so you can see
 * the chunks being generated (useful for relog-loading / chunk exploration), then restores it
 * after a short time. Notifies once per drop.
 *
 * Clean-room port of the obfuscated Zelith "AutoRender" module.
 */
public final class AutoRenderModule extends Module {

    private final BoolSetting notification = add(new BoolSetting("notification", "Notification", true)
        .description("Notify when the temporary render boost triggers.")
        .group("General"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Notification sound", true)
        .description("Play a sound with the notification.")
        .group("General"));
    private final autismclient.api.module.IntSetting holdTicks = add(new autismclient.api.module.IntSetting(
            "hold-ticks", "Boost hold (ticks)", 20, 5, 200, 5)
        .description("How long the temporary render boost lasts.")
        .group("General"));

    private boolean triggered = false;
    private boolean boosting = false;
    private boolean notified = false;
    private int hold = 0;
    private int savedSimDistance = -1;

    public AutoRenderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-auto-render", "Auto Render", category,
            "Briefly boosts render distance when you drop below Y=-2.");
    }

    @Override
    public void onEnable() {
        triggered = false;
        boosting = false;
        notified = false;
        hold = 0;
        savedSimDistance = -1;
    }

    @Override
    public void onDisable() {
        restore();
        triggered = false;
        boosting = false;
        notified = false;
        hold = 0;
    }

    private void restore() {
        if (savedSimDistance >= 0) {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.options.simulationDistance().set(savedSimDistance);
            } catch (Throwable ignored) {}
            savedSimDistance = -1;
        }
        boosting = false;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean below = mc.player.getY() < -2.0;
        if (!below) {
            triggered = false;
            notified = false;
        }

        if (below && !triggered && !boosting) {
            boosting = true;
            hold = 0;
            triggered = true;
            try {
                savedSimDistance = mc.options.simulationDistance().get();
                mc.options.simulationDistance().set(2);
            } catch (Throwable ignored) {}
            if (notification.get() && !notified) {
                AutismClientMessaging.sendPrefixed("§7Auto Render: boosting render/sim distance below Y=-2.");
                if (sound.get()) {
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }
                notified = true;
            }
        }

        if (boosting) {
            hold++;
            if (hold >= Math.max(5, holdTicks.get())) {
                restore();
            }
        }
    }
}
