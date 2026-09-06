package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Recentres the player onto the middle of the block they're standing on (sneak-walked to the
 * block centre). Used before starting a precise dig / direction change so movement is aligned to
 * the block grid.
 *
 * Drives the vanilla movement keys toward the block centre each tick until within tolerance.
 *
 * Port of the Krypton AI SimpleSneakCentering (dev.FORE.AI) to Mojang 26.2 mappings.
 */
public final class SimpleSneakCentering {
    private static final double TOLERANCE = 0.15;

    private final Minecraft mc = Minecraft.getInstance();
    private boolean active = false;
    private BlockPos targetBlock = null;
    private double targetX;
    private double targetZ;
    private int tickCount = 0;

    /** Begin centering on the player's current block. Returns false if already centered. */
    public boolean startCentering() {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        this.targetBlock = player.blockPosition();
        this.targetX = targetBlock.getX() + 0.5;
        this.targetZ = targetBlock.getZ() + 0.5;
        double offsetX = Math.abs(player.getX() - targetX);
        double offsetZ = Math.abs(player.getZ() - targetZ);
        if (offsetX <= TOLERANCE && offsetZ <= TOLERANCE) return false;
        this.active = true;
        this.tickCount = 0;
        return true;
    }

    /** Drive movement keys toward the block centre. Returns false once centered / inactive. */
    public boolean tick() {
        if (!active || mc.player == null || mc.options == null) return false;
        LocalPlayer player = mc.player;
        tickCount++;
        double worldOffsetX = player.getX() - targetX;
        double worldOffsetZ = player.getZ() - targetZ;

        if (Math.abs(worldOffsetX) <= TOLERANCE && Math.abs(worldOffsetZ) <= TOLERANCE) {
            stopCentering();
            return false;
        }

        releaseAllKeys();
        mc.options.keyShift.setDown(true);

        float yaw = player.getYRot();
        double yawRad = Math.toRadians(yaw);
        double moveX = -worldOffsetX;
        double moveZ = -worldOffsetZ;
        double relativeForward = moveX * -Math.sin(yawRad) + moveZ * Math.cos(yawRad);
        double relativeStrafe = moveX * -Math.cos(yawRad) + moveZ * -Math.sin(yawRad);

        if (Math.abs(relativeForward) > 0.075) {
            if (relativeForward > 0) mc.options.keyUp.setDown(true);
            else mc.options.keyDown.setDown(true);
        }
        if (Math.abs(relativeStrafe) > 0.075) {
            if (relativeStrafe > 0) mc.options.keyLeft.setDown(true);
            else mc.options.keyRight.setDown(true);
        }
        return true;
    }

    public void stopCentering() {
        active = false;
        releaseAllKeys();
    }

    private void releaseAllKeys() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    public boolean isActive() { return active; }
}
