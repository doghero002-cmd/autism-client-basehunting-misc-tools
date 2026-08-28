package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.EnumSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Flight+.
 *
 * Elytra-boost fly ported from MeteorPlus' "flight+" module. Two modes that both use a quick
 * elytra equip + start-fall-flying to gain creative-like flight:
 *  - MATRIX_EXPLOIT: hold directional keys to fly at the configured speed, with an up/down
 *    bounce cycle that keeps you airborne.
 *  - MATRIX_EXPLOIT_2: a tighter elytra-start sequence with its own speed profile.
 *
 * Requires an elytra in your inventory for the boost start. Ported to the AUTISM module API.
 */
public final class FlightPlusModule extends Module {

    public enum FlyMode { MATRIX_EXPLOIT, MATRIX_EXPLOIT_2 }

    private final EnumSetting<FlyMode> flyMode = add(new EnumSetting<>(
            "mode", "Mode", FlyMode.MATRIX_EXPLOIT, FlyMode.values())
        .description("The fly method.")
        .group("General"));
    private final DoubleSetting speed1 = add(new DoubleSetting(
            "speed-1", "Speed #1", 1.25, 0.0, 2500.0, 0.05)
        .description("Fly speed for Matrix Exploit / the second speed for Matrix Exploit 2.")
        .group("General"));
    private final DoubleSetting speed2 = add(new DoubleSetting(
            "speed-2", "Speed #2", 0.3, 0.0, 5.0, 0.05)
        .description("Fly speed for Matrix Exploit 2.")
        .group("General")
        .visibleWhen(() -> flyMode.get() == FlyMode.MATRIX_EXPLOIT_2));
    private final BoolSetting requireElytra = add(new BoolSetting(
            "require-elytra", "Require elytra", true)
        .description("Only run if an elytra is present (equipped or in inventory).")
        .group("General"));

    private int tick = 0;
    private int tick2 = 0;
    private int seqTicks = 0;

    public FlightPlusModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":flight-plus", "Flight+", category,
            "Elytra-boost fly (ported from MeteorPlus flight+).");
    }

    @Override
    public void onEnable() {
        tick = 0;
        tick2 = 0;
        seqTicks = 0;
        if (requireElytra.get() && !hasElytra()) {
            autismclient.util.AutismClientMessaging.sendPrefixed("§cFlight+: no elytra found.");
            setEnabledSilently(false);
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !mc.player.isSpectator()) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().setFlyingSpeed(0.05f);
            if (!mc.player.getAbilities().instabuild) {
                mc.player.getAbilities().mayfly = false;
            }
        }
    }

    private static boolean hasElytra() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.ELYTRA)) return true;
        }
        return mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static void startFallFlying(Minecraft mc) {
        if (mc.getConnection() != null && mc.player != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    @Override
    public void preMovementTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (flyMode.get() == FlyMode.MATRIX_EXPLOIT) {
            tickMatrixExploit(mc);
        } else {
            tickMatrixExploit2(mc);
        }
    }

    /** Matrix Exploit: creative-style directional flight with an up/down bounce to stay airborne. */
    private void tickMatrixExploit(Minecraft mc) {
        float yaw = mc.player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        double s = speed1.get();
        double velX = 0, velZ = 0;
        if (mc.options.keyUp.isDown()) { velX += forward.x * s; velZ += forward.z * s; }
        if (mc.options.keyDown.isDown()) { velX -= forward.x * s; velZ -= forward.z * s; }
        if (mc.options.keyRight.isDown()) { velX += right.x * s; velZ += right.z * s; }
        if (mc.options.keyLeft.isDown()) { velX -= right.x * s; velZ -= right.z * s; }

        // Bounce cycle keeps you from falling.
        if (tick2 >= 0) {
            mc.player.setDeltaMovement(velX, 0.100000001490116, velZ);
            tick2++;
            if (tick2 >= 13) {
                mc.player.setDeltaMovement(velX, -0.060000001490116, velZ);
                if (tick2 >= 16) tick2 = 0;
            }
        }

        // Kick off the elytra boost once at start.
        if (tick == 0 && hasElytra()) {
            startFallFlying(mc);
            startFallFlying(mc);
            tick = 21;
        } else if (tick > 0) {
            tick--;
        }
    }

    /** Matrix Exploit 2: a timed elytra-start sequence with its own speed profile. */
    private void tickMatrixExploit2(Minecraft mc) {
        double s2 = speed2.get();
        switch (seqTicks) {
            case 0 -> {
                startFallFlying(mc);
                seqTicks++;
            }
            case 1, 2 -> {
                mc.player.getAbilities().setFlyingSpeed((float) s2);
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.100000001490116, mc.player.getDeltaMovement().z);
                seqTicks++;
            }
            case 3 -> {
                startFallFlying(mc);
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.100000001490116, mc.player.getDeltaMovement().z);
                seqTicks++;
            }
            default -> {
                if (seqTicks >= 13 && seqTicks <= 16) {
                    seqTicks++;
                    mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, -0.060000001490116, mc.player.getDeltaMovement().z);
                    mc.player.getAbilities().setFlyingSpeed((float) (s2 - 0.1f));
                } else if (seqTicks <= 16) {
                    seqTicks++;
                    mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.100000001490116, mc.player.getDeltaMovement().z);
                    mc.player.getAbilities().setFlyingSpeed((float) s2);
                } else {
                    seqTicks = 0;
                }
            }
        }
    }

    /** Drive horizontal velocity from the directional keys during the bounce cycle (Matrix Exploit). */
    @Override
    public Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        if (type != MoverType.SELF) return movement;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || flyMode.get() != FlyMode.MATRIX_EXPLOIT) return movement;

        float yaw = mc.player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        double s = speed1.get();
        double x = 0, z = 0;
        if (mc.options.keyUp.isDown()) { x += forward.x * s; z += forward.z * s; }
        if (mc.options.keyDown.isDown()) { x -= forward.x * s; z -= forward.z * s; }
        if (mc.options.keyRight.isDown()) { x += right.x * s; z += right.z * s; }
        if (mc.options.keyLeft.isDown()) { x -= right.x * s; z -= right.z * s; }
        return new Vec3(x, movement.y, z);
    }
}
