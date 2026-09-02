package com.autism.seedcracker.modules;

import java.util.Random;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/**
 * Anti AFK.
 *
 * Performs small random actions so the server does not kick you for being idle. Each action is
 * optional and can be toggled: jumping, swinging your arm, sneaking for a few ticks, strafing
 * left/right, and slowly spinning your view.
 *
 * Clean-room port of the obfuscated Zelith "AntiAFK" module.
 */
public final class AntiAFKModule extends Module {

    private final BoolSetting jump = add(new BoolSetting("jump", "Jump", true)
        .description("Randomly hop every now and then.")
        .group("General"));
    private final BoolSetting swing = add(new BoolSetting("swing", "Swing", false)
        .description("Randomly swing your arm.")
        .group("General"));
    private final BoolSetting sneak = add(new BoolSetting("sneak", "Sneak", false)
        .description("Hold sneak for a few ticks at a time.")
        .group("General"));
    private final IntSetting sneakTicks = add(new IntSetting("sneak-ticks", "Sneak ticks", 5, 1, 20, 1)
        .description("How many ticks to hold sneak for.")
        .group("General"));
    private final BoolSetting strafe = add(new BoolSetting("strafe", "Strafe", false)
        .description("Alternate strafing left and right.")
        .group("General"));
    private final BoolSetting spin = add(new BoolSetting("spin", "Spin", true)
        .description("Slowly rotate your view.")
        .group("General"));
    private final IntSetting spinSpeed = add(new IntSetting("spin-speed", "Spin speed", 7, 1, 30, 1)
        .description("Degrees to rotate per tick while spinning.")
        .group("General"));

    private final Random random = new Random();

    private int sneakTimer;
    private int strafeTimer;
    private boolean strafeLeft;
    private float yaw;

    public AntiAFKModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-anti-afk", "Anti AFK", category,
            "Performs small random actions so you do not get kicked for being AFK.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        yaw = mc.player != null ? mc.player.getYRot() : 0.0f;
        sneakTimer = 0;
        strafeTimer = 0;
        strafeLeft = false;
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.options.keyShift.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (jump.get()) {
            if (mc.options.keyJump.isDown()) {
                mc.options.keyJump.setDown(false);
            } else if (random.nextInt(99) == 0) {
                mc.options.keyJump.setDown(true);
            }
        }

        if (swing.get() && random.nextInt(99) == 0) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        if (sneak.get()) {
            if (sneakTimer >= sneakTicks.get()) {
                mc.options.keyShift.setDown(false);
                if (random.nextInt(99) == 0) {
                    sneakTimer = 0;
                }
            } else {
                mc.options.keyShift.setDown(true);
                sneakTimer++;
            }
        }

        if (strafe.get() && strafeTimer-- <= 0) {
            mc.options.keyLeft.setDown(!strafeLeft);
            mc.options.keyRight.setDown(strafeLeft);
            strafeLeft = !strafeLeft;
            strafeTimer = 20;
        }

        if (spin.get()) {
            yaw += spinSpeed.get();
            mc.player.setYRot(yaw);
        }
    }
}
