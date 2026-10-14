package com.autism.seedcracker.util.pure;

import java.util.ArrayList;
import java.util.List;
import com.autism.seedcracker.finder.PrimePacketTracker;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimeLightTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void realBlockKindsSeparateOrdinaryLampsFromRedstone() {
        assertTrue(PrimePacketTracker.lamp(Blocks.TORCH.defaultBlockState()));
        assertTrue(PrimePacketTracker.lamp(Blocks.WALL_TORCH.defaultBlockState()));
        assertTrue(PrimePacketTracker.lamp(Blocks.SOUL_WALL_TORCH.defaultBlockState()));
        assertFalse(PrimePacketTracker.lamp(Blocks.REDSTONE_TORCH.defaultBlockState()));
        assertTrue(PrimePacketTracker.redstone(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertFalse(PrimePacketTracker.redstone(Blocks.STONE.defaultBlockState()));
    }

    @Test void comparesBothNibblesWithoutSignExtension() {
        var previous = new byte[2048];
        var incoming = new byte[2048];
        incoming[100] = (byte) 0xAF;
        var cells = new ArrayList<Integer>();
        assertEquals(2, PrimeLight.changes(previous, incoming, cells::add));
        assertEquals(List.of(200, 201), cells);
        assertEquals(15, PrimeLight.value(incoming, 200));
        assertEquals(10, PrimeLight.value(incoming, 201));
        assertEquals(0, PrimeLight.changes(incoming, incoming.clone(), index -> fail("Unchanged cell reported")));
    }

    @Test void unknownAndMalformedPayloadsCannotCreateChanges() {
        assertEquals(0, PrimeLight.changes(null, new byte[2048], index -> fail("Unknown baseline reported")));
        assertEquals(0, PrimeLight.changes(new byte[2], new byte[2048], index -> fail("Malformed baseline reported")));
        var previous = new byte[2048];
        previous[0] = 5;
        assertEquals(1, PrimeLight.changes(previous, new byte[2048], index -> assertEquals(0, index)));
        assertThrows(IllegalArgumentException.class, () -> PrimeLight.value(previous, 4096));
    }

    @Test void referenceSourceExclusionsUseManhattanReachAndLightStrength() {
        assertTrue(PrimeLight.amethystExplains(5, 5));
        assertFalse(PrimeLight.amethystExplains(6, 0));
        assertFalse(PrimeLight.amethystExplains(5, 6));
        assertTrue(PrimeLight.sourceExplains(9, 4, 5));
        assertFalse(PrimeLight.sourceExplains(9, 4, 6));
        assertTrue(PrimeLight.sourceExplains(14, 10, 4));
        assertFalse(PrimeLight.sourceExplains(14, 15, 0));
        assertFalse(PrimeLight.sourceExplains(15, 1, 0));
    }
}