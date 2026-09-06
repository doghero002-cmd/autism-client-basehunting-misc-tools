package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vulcan Boat Fly.
 *
 * Flies a ridden boat by directly setting its velocity: horizontal bursts on a 5-tick cadence,
 * upward boost while jumping (with a cooldown), downward pull while sprinting, plus a periodic
 * small "settle" nudge that keeps Vulcan's boat check from accumulating. Applies a client speed
 * timer while moving for extra throughput.
 *
 * Port of the Selena/Acid "VulcanBoatFlight" module (1.21.1, Yarn) to the AUTISM API (Mojang
 * mappings). Meteor's BoatMoveEvent is reproduced via the onPlayerMove hook, and its Timer module
 * via the built-in speed-timer hooks. Very high-speed / detectable - use at your own risk.
 */
public final class VulcanBoatFlyModule extends Module {

    private final DoubleSetting timer = add(new DoubleSetting(
            "timer", "Timer", 5.0, 1.0, 5.0, 0.1)
        .description("Timer override while moving in the boat.")
        .group("General"));
    private final DoubleSetting speed = add(new DoubleSetting(
            "speed", "Speed (bps)", 20.0, 0.0, 79.0, 1.0)
        .description("Horizontal speed in blocks per second.")
        .group("General"));
    private final DoubleSetting upwardSpeed = add(new DoubleSetting(
            "upward-speed", "Upward speed mult", 48.0, 5.0, 48.0, 1.0)
        .description("Upward speed multiplier (while jumping).")
        .group("General"));
    private final DoubleSetting downwardSpeed = add(new DoubleSetting(
            "downward-speed", "Downward speed", 100.0, 0.0, 100.0, 1.0)
        .description("Downward speed in blocks per second (while sprinting).")
        .group("General"));

    private int verticalMoveCooldown = 0;

    public VulcanBoatFlyModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":vulcan-boat-fly", "Vulcan Boat Fly", category,
            "Fly a boat at high speed (Vulcan bypass). Jump = up, sprint = down. WARNING: very detectable.");
    }

    @Override
    public void onDisable() {
        verticalMoveCooldown = 0;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (verticalMoveCooldown > 0) verticalMoveCooldown--;

        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null || vehicle.getControllingPassenger() != mc.player) return;

        // Periodic settle nudge every 10 ticks + continuous small downward pull keeps the
        // anti-cheat's boat motion model satisfied.
        long t = mc.level.getGameTime();
        if (t % 10L == 2L) {
            moveVertically(mc, vehicle, 0.505);
        }
        float mult = verticalMoveCooldown > 0 ? 11.0f : 1.0f;
        moveVertically(mc, vehicle, -0.0505 * mult);
    }

    /** Nudges the boat vertically if the path is clear (mirrors the original's collision check). */
    private void moveVertically(Minecraft mc, Entity vehicle, double amount) {
        AABB box = mc.player.getBoundingBox().expandTowards(0, amount, 0);
        if (mc.level.getBlockCollisions(null, box).iterator().hasNext()) return;
        vehicle.setPos(vehicle.getX(), vehicle.getY() + amount, vehicle.getZ());
    }

    @Override
    public Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return movement;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null || vehicle.getControllingPassenger() != mc.player) return movement;

        long t = mc.level.getGameTime();
        vehicle.setYRot(mc.player.getYRot());

        Vec3 horizontal = horizontalVelocity(mc, speed.get() * 5.0);
        double velX = t % 5L == 0L ? horizontal.x : 0.0;
        double velZ = t % 5L == 0L ? horizontal.z : 0.0;
        double velY = 0.0;

        if (mc.options.keyJump.isDown() && verticalMoveCooldown <= 0 && t % 5L != 0L) {
            velY += upwardSpeed.get() / 2.5;
            verticalMoveCooldown = 8;
        }
        if (mc.options.keySprint.isDown() && t % 5L != 0L) {
            velY -= (downwardSpeed.get() / 20.0) * 1.2;
        }

        vehicle.setDeltaMovement(velX, velY, velZ);
        return movement;
    }

    /** Horizontal velocity vector from yaw (mirrors Meteor's PlayerUtils.getHorizontalVelocity). */
    private static Vec3 horizontalVelocity(Minecraft mc, double speed) {
        float yaw = mc.player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        Vec3 vel = new Vec3(0, 0, 0);
        if (mc.options.keyUp.isDown()) vel = vel.add(forward);
        if (mc.options.keyDown.isDown()) vel = vel.subtract(forward);
        if (mc.options.keyRight.isDown()) vel = vel.add(Vec3.directionFromRotation(0, yaw + 90));
        if (mc.options.keyLeft.isDown()) vel = vel.subtract(Vec3.directionFromRotation(0, yaw + 90));
        Vec3 flat = new Vec3(vel.x, 0, vel.z);
        if (flat.lengthSqr() == 0) return Vec3.ZERO;
        return flat.normalize().scale(speed / 20.0);
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
        return timer.get().floatValue();
    }
}
