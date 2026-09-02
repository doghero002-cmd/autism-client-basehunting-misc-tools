package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

/**
 * Swing Speed.
 *
 * Scales the speed of your arm-swing (attack) animation. A multiplier above 1.0 makes the swing
 * play faster, below 1.0 makes it slower. The base swing lasts a handful of ticks; this advances
 * (or holds back) the swing timer each tick to reach the desired effective speed.
 *
 * Clean-room port of the obfuscated Zelith "SwingSpeed" module.
 */
public final class SwingSpeedModule extends Module {

    private final DoubleSetting swingSpeed = add(new DoubleSetting(
            "swing-speed", "Swing speed", 1.0, 0.1, 2.0, 0.1)
        .description("Arm-swing speed multiplier (1.0 = normal).")
        .group("General"));

    public SwingSpeedModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-swing-speed", "Swing Speed", category,
            "Adjusts the speed of your arm-swing animation.");
    }

    /** The configured swing speed multiplier, clamped to the slider range. */
    public float swingSpeed() {
        float value = swingSpeed.get().floatValue();
        return Math.max(0.1f, Math.min(2.0f, value));
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.swinging) return;

        float multiplier = swingSpeed();
        if (multiplier == 1.0f) return;

        if (multiplier > 1.0f) {
            // Advance the swing timer by the extra whole ticks so the animation finishes sooner.
            mc.player.swingTime += (int) Math.floor(multiplier - 1.0f);
        } else if (mc.player.swingTime > 0) {
            // Hold the swing back so the animation plays slower.
            mc.player.swingTime -= 1;
            if (mc.player.swingTime < 0) mc.player.swingTime = 0;
        }
    }
}
