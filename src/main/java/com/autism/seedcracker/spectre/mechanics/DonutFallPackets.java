package com.autism.seedcracker.spectre.mechanics;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/** Keeps the original movement/rotation/collision data, including another module's silent aim. */
public final class DonutFallPackets {
    private DonutFallPackets() {}

    public static ServerboundMovePlayerPacket.PosRot replacement(ServerboundMovePlayerPacket original,
            double fallbackX, double fallbackY, double fallbackZ, float fallbackYaw, float fallbackPitch,
            DonutFallState.Adjustment adjustment) {
        if (adjustment == null) return null;
        double x = original.getX(fallbackX), y = original.getY(fallbackY) + adjustment.yOffset();
        double z = original.getZ(fallbackZ);
        float yaw = original.getYRot(fallbackYaw), pitch = original.getXRot(fallbackPitch);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        return new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, false,
            original.horizontalCollision());
    }
}
