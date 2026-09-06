package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Vulcan Boat Jump.
 *
 * Rides a boat briefly (the boat keeps a tiny upward velocity so it stays valid), then flings the
 * player in the look direction the moment they dismount, and auto-disables. A one-shot "get
 * launched" bypass.
 *
 * Port of the Selena/Acid "VulcanBoatJump" module (1.21.1, Yarn) to the AUTISM API (Mojang
 * mappings). Meteor's BoatMoveEvent is reproduced via the onPlayerMove hook and its Timer module
 * via the built-in speed-timer hooks. Detectable - use at your own risk.
 */
public final class VulcanBoatJumpModule extends Module {

    private final DoubleSetting range = add(new DoubleSetting(
            "range", "Velocity", 5.0, 0.0, 20.0, 0.5)
        .description("Horizontal launch velocity on dismount.")
        .group("General"));
    private final DoubleSetting upVelocity = add(new DoubleSetting(
            "up-velocity", "Up Velocity", 5.0, 0.0, 20.0, 0.5)
        .description("Vertical launch velocity on dismount.")
        .group("General"));

    private boolean start = true;

    public VulcanBoatJumpModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":vulcan-boat-jump", "Vulcan Boat Jump", category,
            "Launch yourself by dismounting a boat (Vulcan bypass). One-shot. WARNING: detectable.");
    }

    @Override
    public void onEnable() {
        start = true;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (mc.player.isPassenger() && isBoat(mc.player.getVehicle())) {
            start = false;
        }
        // Once we had boarded (start==false) and are no longer riding, fling and disable.
        if (!mc.player.isPassenger() && !start) {
            fly(mc, range.get());
            setEnabled(false);
        }
    }

    private void fly(Minecraft mc, double speed) {
        float yaw = mc.player.getYRot();
        double rad = Math.toRadians(yaw);
        Vec3 dir = new Vec3(-Math.sin(rad), upVelocity.get(), Math.cos(rad)).normalize().scale(speed);
        mc.player.setDeltaMovement(dir);
        mc.player.hurtMarked = true;
    }

    private static boolean isBoat(Entity e) {
        return e instanceof AbstractBoat;
    }

    @Override
    public Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return movement;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null || vehicle.getControllingPassenger() != mc.player) return movement;

        vehicle.setYRot(mc.player.getYRot());
        vehicle.setDeltaMovement(0.0, 0.1, 0.0);
        return movement;
    }

    @Override
    public boolean shouldApplySpeedTimer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        boolean moving = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        return moving && !mc.options.keyJump.isDown() && !mc.options.keySprint.isDown()
            && mc.player.getVehicle() != null;
    }

    @Override
    public float speedTimerMultiplier() {
        return 0.5f;
    }
}
