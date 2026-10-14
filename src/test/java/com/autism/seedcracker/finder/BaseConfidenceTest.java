package com.autism.seedcracker.finder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseConfidenceTest {
    @Test void measuresFullHeightNaturalChunkScanCost() {
        var sections = new LevelChunkSection[24];
        for (int index = 0; index < sections.length; index++) sections[index] = new LevelChunkSection(
            new PalettedContainer<>(Blocks.STONE.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int iteration = 0; iteration < 100; iteration++) BaseConfidence.scoreSections(sections);
        var elapsed = new long[100];
        for (int iteration = 0; iteration < elapsed.length; iteration++) {
            long start = System.nanoTime();
            assertEquals(0, BaseConfidence.scoreSections(sections).score());
            elapsed[iteration] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(elapsed);
        System.out.printf(java.util.Locale.ROOT, "BaseConfidence fixture: 24 stone sections, median=%.3fms p95=%.3fms%n",
            elapsed[50] / 1_000_000.0, elapsed[94] / 1_000_000.0);
    }

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LevelChunkSection section(Block... blocks) {
        var states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        var section = new LevelChunkSection(states, null);
        for (int index = 0; index < blocks.length; index++) section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, blocks[index].defaultBlockState());
        return section;
    }

    @Test void nonEntityWorkstationsAndAnvilVariantsAreCountedOnce() {
        assertEquals(15, BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.CRAFTING_TABLE, Blocks.GRINDSTONE, Blocks.DAMAGED_ANVIL)
        }).score());
        assertEquals(5, BaseConfidence.scoreSections(new LevelChunkSection[] { section(Blocks.BREWING_STAND) }).score());
    }

    @Test void dyedStorageHasTheSameEvidenceAsUncolouredStorage() {
        assertEquals(24, BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.SHULKER_BOX, Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.black())
        }).score());
        assertEquals(8, BaseConfidence.scoreSections(new LevelChunkSection[] { section(Blocks.CHEST) }).score());
    }

    @Test void plainStoneAndSparseMineshaftSignalsStayBelowTheAlertThreshold() {
        var stone = new LevelChunkSection(new PalettedContainer<>(Blocks.STONE.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        assertEquals(0, BaseConfidence.scoreSections(new LevelChunkSection[] { stone }).score());
        assertTrue(BaseConfidence.scoreSections(new LevelChunkSection[] {
            section(Blocks.CHEST, Blocks.TORCH, Blocks.RAIL, Blocks.OAK_PLANKS)
        }).score() < 80);
    }

    @Test void mixedStorageRoomRetainsStrongEvidence() {
        var result = BaseConfidence.scoreSections(new LevelChunkSection[] { section(
            Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.black(), Blocks.HOPPER,
            Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND,
            Blocks.TORCH, Blocks.TORCH, Blocks.LANTERN, Blocks.LANTERN, Blocks.REDSTONE_LAMP) });
        assertEquals(83, result.score());
        assertEquals("6 storage", result.topReason());
    }
}