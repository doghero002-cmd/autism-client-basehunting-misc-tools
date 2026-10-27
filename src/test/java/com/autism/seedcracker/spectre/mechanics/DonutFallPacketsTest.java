package com.autism.seedcracker.spectre.mechanics;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DonutFallPacketsTest {
    @Test void preservesSentCoordinatesSilentRotationAndHorizontalCollision() {
        var original = new ServerboundMovePlayerPacket.PosRot(1, 64, 3, 120, -35, true, true);
        var replacement = DonutFallPackets.replacement(original, 9, 10, 11, 12, 13,
            new DonutFallState.Adjustment(1, 1, 1.0E-8));
        assertNotNull(replacement);
        assertEquals(1, replacement.getX(0));
        assertEquals(64 + 1.0E-8, replacement.getY(0));
        assertEquals(3, replacement.getZ(0));
        assertEquals(120, replacement.getYRot(0));
        assertEquals(-35, replacement.getXRot(0));
        assertTrue(replacement.horizontalCollision());
        assertFalse(replacement.isOnGround());
        assertEquals(64, original.getY(0));
        assertTrue(original.isOnGround());
    }

    @Test void suppliesOnlyDataMissingFromPartialVanillaPackets() {
        var original = new ServerboundMovePlayerPacket.Rot(120, -35, true, false);
        var replacement = DonutFallPackets.replacement(original, 9, 10, 11, 12, 13,
            new DonutFallState.Adjustment(1, 2, 0));
        assertEquals(9, replacement.getX(0));
        assertEquals(10, replacement.getY(0));
        assertEquals(11, replacement.getZ(0));
        assertEquals(120, replacement.getYRot(0));
        assertEquals(-35, replacement.getXRot(0));
        assertFalse(replacement.horizontalCollision());
    }

    @Test void idleOrNonfinitePacketsAreNotRewritten() {
        var packet = new ServerboundMovePlayerPacket.StatusOnly(true, false);
        assertNull(DonutFallPackets.replacement(packet, 1, 2, 3, 4, 5, null));
        var adjustment = new DonutFallState.Adjustment(1, 0, 0);
        assertNull(DonutFallPackets.replacement(packet, Double.NaN, 2, 3, 4, 5, adjustment));
        assertNull(DonutFallPackets.replacement(packet, 1, 2, 3, Float.POSITIVE_INFINITY, 5, adjustment));
    }
}
