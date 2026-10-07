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

    public SwingSpeedModule() {
        super(SeedcrackerAddon.ID + ":z-swing-speed", "Swing Speed",
            "Adjusts the speed of your arm-swing animation.");
    }

    /** The configured swing speed multiplier, clamped to the slider range. */
    public float swingSpeed() {
        float value = swingSpeed.get().floatValue();
        return Math.max(0.1f, Math.min(2.0f, value));
    }

    /** Fractional speed-up carry: 1.5x = +0.5/tick accumulated, applied as whole ticks. */
    private float speedCarry = 0f;
    /** Slow-down carry: 0.5x holds the timer back every other tick, not every tick. */
    private float slowCarry = 0f;

    @Override
    public void onEnable() {
        speedCarry = 0f;
        slowCarry = 0f;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.swinging) return;

        float multiplier = swingSpeed();
        if (multiplier == 1.0f) return;

        if (multiplier > 1.0f) {
            // Accumulate the fractional extra across ticks: floor(mult-1) alone was 0 for the
            // whole 1.1-1.9 range, making most of the slider dead.
            speedCarry += multiplier - 1.0f;
            int whole = (int) speedCarry;
            if (whole > 0) {
                speedCarry -= whole;
                mc.player.swingTime += whole;
            }
        } else if (mc.player.swingTime > 0) {
            // Hold back only (1-mult) of the time: an every-tick -1 exactly cancelled vanilla's
            // ++ and froze the animation mid-arc forever.
            slowCarry += 1.0f - multiplier;
            if (slowCarry >= 1.0f) {
                slowCarry -= 1.0f;
                mc.player.swingTime -= 1;
                if (mc.player.swingTime < 0) mc.player.swingTime = 0;
            }
        }
    }
}
