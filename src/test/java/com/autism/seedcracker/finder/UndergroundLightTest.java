package com.autism.seedcracker.finder;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UndergroundLightTest {
    @Test void handoffIsReadOnlyAndCannotBeCollectedAgain() {
        UndergroundLight snapshot = new UndergroundLight(new net.minecraft.world.level.ChunkPos(0, 0), 0, 5);
        Map<BlockPos, Integer> transferred = snapshot.hits();
        assertSame(transferred, snapshot.hits());
        assertThrows(UnsupportedOperationException.class, () -> transferred.put(new BlockPos(0, -20, 0), 14));
        assertThrows(IllegalStateException.class, () -> snapshot.collect(null, 1));
    }

    @Test void excludesSkyDominatedAndBelowThresholdCells() {
        assertTrue(UndergroundLight.qualifies(5, 4, 5));
        assertFalse(UndergroundLight.qualifies(5, 5, 5));
        assertFalse(UndergroundLight.qualifies(4, 0, 5));
    }

    @Test void denoiseRemovesIsolatedWeakRegionButKeepsOneNearStrongerLight() {
        BlockPos weak = new BlockPos(0, -20, 0);
        assertTrue(UndergroundLight.surface(Map.of(weak, 6, weak.below(), 5), true, 5).isEmpty());
        assertEquals(2, UndergroundLight.surface(Map.of(weak, 6, weak.above(), 14), true, 5).values().stream().mapToInt(java.util.Set::size).sum());
        assertEquals(1, UndergroundLight.surface(Map.of(weak, 6), false, 5).get(6).size());
    }

    @Test void interiorCellsAreNotRenderedAndOutputIsBounded() {
        Map<BlockPos, Integer> cube = new HashMap<>();
        for (int offsetX = -1; offsetX <= 1; offsetX++) for (int offsetY = -1; offsetY <= 1; offsetY++) for (int offsetZ = -1; offsetZ <= 1; offsetZ++) cube.put(new BlockPos(offsetX, -20 + offsetY, offsetZ), 14);
        assertEquals(26, UndergroundLight.surface(cube, false, 5).get(14).size());
        assertFalse(UndergroundLight.surface(cube, false, 5).get(14).contains(new BlockPos(0, -20, 0)));
        Map<BlockPos, Integer> many = new HashMap<>();
        for (int index = 0; index < 13_000; index++) many.put(new BlockPos(index * 2, -20, 0), 14);
        assertEquals(12_000, UndergroundLight.surface(many, false, 5).get(14).size());
    }
}