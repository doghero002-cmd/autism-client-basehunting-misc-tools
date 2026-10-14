package com.autism.seedcracker.finder;

import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoneStackScannerTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void enclosedStackIsFoundButUncappedOrMixedWallStackIsNot() {
        StoneStackScanner valid = new StoneStackScanner(new ChunkPos(0, 0), 2, pos -> pos.getX() == 8 && pos.getZ() == 8 && pos.getY() >= 9 && pos.getY() <= 12
            ? (pos.getY() == 9 || pos.getY() == 12 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState() : Blocks.DIORITE.defaultBlockState());
        assertTrue(valid.step(Long.MAX_VALUE)); assertTrue(valid.complete()); assertEquals(Set.of(new BlockPos(8, 10, 8)), valid.stacks());
        StoneStackScanner uncapped = new StoneStackScanner(new ChunkPos(0, 0), 2, pos -> pos.getX() == 8 && pos.getZ() == 8 && pos.getY() >= 10 && pos.getY() <= 11 ? Blocks.STONE.defaultBlockState() : Blocks.DIORITE.defaultBlockState());
        uncapped.step(Long.MAX_VALUE); assertTrue(uncapped.stacks().isEmpty());
        StoneStackScanner mixed = new StoneStackScanner(new ChunkPos(0, 0), 2, pos -> pos.getX() == 7 ? Blocks.GRANITE.defaultBlockState() : pos.getX() == 8 && pos.getZ() == 8 ? Blocks.STONE.defaultBlockState() : Blocks.DIORITE.defaultBlockState());
        mixed.step(Long.MAX_VALUE); assertTrue(mixed.stacks().isEmpty());
    }

    @Test void missingNeighborsDeferAndExpiredDeadlineDoesNotAdvance() {
        StoneStackScanner scanner = new StoneStackScanner(new ChunkPos(0, 0), 2, pos -> pos.getX() < 0 ? null : Blocks.STONE.defaultBlockState());
        assertFalse(scanner.step(0)); assertTrue(scanner.step(Long.MAX_VALUE)); assertFalse(scanner.complete());
    }

    @Test void connectedHeatPatchesKeepAllEqualMaximaNotLowerNeighbors() {
        ChunkPos first = new ChunkPos(0, 0), tied = new ChunkPos(1, 0), lower = new ChunkPos(2, 0), separate = new ChunkPos(10, 10);
        assertEquals(Set.of(first, tied, separate), StoneStackScanner.hottest(Map.of(first, 4, tied, 4, lower, 2, separate, 1)));
    }

    @Test void boundedPrefixSpreadingMatchesDirectNeighborhoodSums() {
        Map<ChunkPos, Integer> samples = Map.of(new ChunkPos(-3, 1), 2, new ChunkPos(-1, 0), 4, new ChunkPos(100, 100), 9);
        ChunkPos center = new ChunkPos(-2, 0);
        Map<ChunkPos, Integer> heat = StoneStackScanner.spreadHeat(samples, center, 3, 2);
        for (int offsetX = -3; offsetX <= 3; offsetX++) for (int offsetZ = -3; offsetZ <= 3; offsetZ++) {
            ChunkPos pos = new ChunkPos(center.x() + offsetX, center.z() + offsetZ);
            int expected = samples.entrySet().stream().filter(entry -> Math.abs(entry.getKey().x() - pos.x()) <= 2 && Math.abs(entry.getKey().z() - pos.z()) <= 2).mapToInt(Map.Entry::getValue).sum();
            assertEquals(expected, heat.getOrDefault(pos, 0));
        }
    }
}