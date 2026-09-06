package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * Grim Trident Fly.
 *
 * Flies by repeatedly applying a riptide-style velocity boost in the look direction on a fixed
 * tick delay, plus a small hop when on the ground. Pairs with the Grim Trident Disabler (which
 * suppresses the move check that would otherwise flag the motion).
 *
 * Port of the Selena/Acid "GrimTridentFly" module (1.21.1, Yarn) to the AUTISM API (Mojang
 * mappings).
 */
public final class GrimTridentFlyModule extends Module {

    private static final float BOOST = 3.0f;

    private final IntSetting tridentDelay = add(new IntSetting(
            "trident-delay", "Delay (ticks)", 0, 0, 20, 1)
        .description("Delay (in ticks) between trident boosts.")
        .group("General"));

    private int currentTick = 0;

    public GrimTridentFlyModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":grim-trident-fly", "Grim Trident Fly [Patched]", category,
            "Fly by spamming Riptide trident boosts. Use together with Grim Trident Disabler. WARNING: likely patched / detectable.");
    }

    @Override
    public void onEnable() {
        currentTick = 0;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (currentTick < tridentDelay.get()) {
            currentTick++;
            return;
        }
        currentTick = 0;

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        float dx = -Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
        float dy = -Mth.sin(pitch * Mth.DEG_TO_RAD);
        float dz = Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        dx *= BOOST / len;
        dy *= BOOST / len;
        dz *= BOOST / len;
        mc.player.push(dx, dy, dz);

        if (mc.player.onGround()) {
            mc.player.move(MoverType.SELF, new Vec3(0.0, 1.1999999, 0.0));
        }
    }
}
