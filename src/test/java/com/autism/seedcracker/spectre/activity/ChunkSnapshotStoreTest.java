package com.autism.seedcracker.spectre.activity;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSnapshotStoreTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] sections(int length) {
        return (PalettedContainer<BlockState>[]) new PalettedContainer<?>[length];
    }
    private static PalettedContainer<BlockState> air() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }
    private static boolean meaningful(BlockState before, BlockState after) {
        return !QuantumRules.ignoredChange(BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString(),
            BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
    }

    @Test void firstLoadsAndAllAirBaselinesAreNotResendDetections() {
        ChunkSnapshotStore store = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
        var current = sections(24);
        current[6] = air();
        current[6].set(1, 2, 3, Blocks.CHEST.defaultBlockState());
        assertNull(store.firstChange(0, 0, current, -4, ChunkSnapshotStoreTest::meaningful));
        store.store(0, 0, sections(24));
        assertNull(store.firstChange(0, 0, current, -4, ChunkSnapshotStoreTest::meaningful));
    }

    @Test void rawResendComparesAnOwnedCopyAndMapsSectionAndNegativeChunkCoordinates() {
        ChunkSnapshotStore store = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
        var current = sections(24);
        current[6] = air();
        current[6].set(2, 3, 4, Blocks.DIRT.defaultBlockState());
        store.store(-2, -3, current);
        current[6].set(2, 3, 4, Blocks.CHEST.defaultBlockState());
        var change = store.firstChange(-2, -3, current, -4, ChunkSnapshotStoreTest::meaningful);
        assertNotNull(change);
        assertEquals(-30, change.worldX());
        assertEquals(35, change.worldY());
        assertEquals(-44, change.worldZ());
        assertSame(Blocks.DIRT.defaultBlockState(), change.oldState());
        assertSame(Blocks.CHEST.defaultBlockState(), change.newState());
        store.store(-2, -3, current);
        assertNull(store.firstChange(-2, -3, current, -4, ChunkSnapshotStoreTest::meaningful));
    }

    @Test void ordinaryUpdatesAreReconciledBeforeAResendAndCanCreateAnAirSection() {
        ChunkSnapshotStore store = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
        var current = sections(24);
        current[6] = air();
        current[6].set(0, 0, 0, Blocks.DIRT.defaultBlockState());
        store.store(0, 0, current);
        current[6].set(0, 0, 0, Blocks.CHEST.defaultBlockState());
        store.update(0, 0, 6, 0, 0, 0, Blocks.CHEST.defaultBlockState());
        current[7] = air();
        current[7].set(1, 1, 1, Blocks.CHEST.defaultBlockState());
        store.update(0, 0, 7, 1, 1, 1, Blocks.CHEST.defaultBlockState());
        assertNull(store.firstChange(0, 0, current, -4, ChunkSnapshotStoreTest::meaningful));
        store.remove(QuantumRules.key(0, 0));
        assertFalse(store.contains(QuantumRules.key(0, 0)));
    }

    @Test void exclusionsDoNotHideTheNextRelevantChangeAndSectionWindowStaysSixThroughTwentyThree() {
        ChunkSnapshotStore store = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
        var current = sections(25);
        current[6] = air();
        current[6].set(0, 0, 0, Blocks.DIRT.defaultBlockState());
        current[6].set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        store.store(0, 0, current);
        current[5] = air(); current[5].set(0, 0, 0, Blocks.CHEST.defaultBlockState());
        current[24] = air(); current[24].set(0, 0, 0, Blocks.CHEST.defaultBlockState());
        current[6].set(0, 0, 0, Blocks.STONE.defaultBlockState());
        current[6].set(1, 0, 0, Blocks.CHEST.defaultBlockState());
        var change = store.firstChange(0, 0, current, -4, ChunkSnapshotStoreTest::meaningful);
        assertNotNull(change);
        assertEquals(1, change.worldX());
        store.store(0, 0, current);
        current[23] = air(); current[23].set(0, 0, 0, Blocks.CHEST.defaultBlockState());
        assertEquals(304, store.firstChange(0, 0, current, -4, ChunkSnapshotStoreTest::meaningful).worldY());
        store.retain(ignored -> false);
        assertFalse(store.contains(QuantumRules.key(0, 0)));
    }

    @Test void snapshotRetentionHasAHardLimitAndClearRemovesEverything() {
        ChunkSnapshotStore store = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
        for (int x = 0; x < 290; x++) store.store(x, 0, sections(24));
        assertFalse(store.contains(QuantumRules.key(0, 0)));
        assertTrue(store.contains(QuantumRules.key(289, 0)));
        store.clear();
        assertFalse(store.contains(QuantumRules.key(289, 0)));
    }
}
