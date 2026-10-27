package com.autism.seedcracker.spectre.palette;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEntityLeaksTest {
    private static BlockEntityLeaks.Entry entry(int x, int y, int z, boolean visible) {
        return new BlockEntityLeaks.Entry(new BlockEntityLeaks.Position(x, y, z), "minecraft:mob_spawner",
            "minecraft:spawner", new BlockEntityLeaks.Spawner("minecraft:zombie", 16, 20), visible);
    }

    @Test void mapsRawTypesWithoutClaimingUnknownTypesAreKnownBlocks() {
        assertEquals("minecraft:spawner", BlockEntityLeaks.blockFromType("minecraft:mob_spawner"));
        assertEquals("minecraft:moving_piston", BlockEntityLeaks.blockFromType("minecraft:piston"));
        assertEquals("minecraft:oak_sign", BlockEntityLeaks.blockFromType("minecraft:sign"));
        assertEquals("minecraft:oak_hanging_sign", BlockEntityLeaks.blockFromType("minecraft:hanging_sign"));
        assertEquals("minecraft:skeleton_skull", BlockEntityLeaks.blockFromType("minecraft:skull"));
        assertEquals("minecraft:white_banner", BlockEntityLeaks.blockFromType("minecraft:banner"));
        assertEquals("minecraft:red_bed", BlockEntityLeaks.blockFromType("minecraft:bed"));
        assertEquals("minecraft:suspicious_sand", BlockEntityLeaks.blockFromType("minecraft:brushable_block"));
        assertEquals("custom:mob_spawner", BlockEntityLeaks.blockFromType("custom:mob_spawner"));
        assertEquals(0xffffffff, BlockEntityLeaks.color("custom:spawner"));
        assertNotEquals(BlockEntityLeaks.color("minecraft:chest"), BlockEntityLeaks.color("minecraft:spawner"));
    }

    @Test void hiddenMaskUpdatesDoNotEraseEvidenceButActualVisibleRemovalDoes() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        var entry = entry(-1, -31, -17, false);
        cache.put(entry);
        for (String masking : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:air", "minecraft:cave_air")) {
            cache.observe(entry.pos(), masking);
            assertEquals(entry, cache.get(entry.pos()));
        }
        cache.observe(entry.pos(), "minecraft:spawner");
        assertTrue(cache.get(entry.pos()).visible());
        cache.observe(entry.pos(), "minecraft:air");
        assertNull(cache.get(entry.pos()));
        cache.put(entry);
        cache.observe(entry.pos(), "minecraft:oak_planks");
        assertNull(cache.get(entry.pos()));
    }

    @Test void refreshUnloadAndEmptyChunkReplacementRemoveStaleEntries() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        var old = entry(-1, -31, -17, false);
        var other = entry(0, 20, 0, false);
        cache.put(old);
        cache.put(other);
        assertEquals(new BlockEntityLeaks.Chunk(-1, -2), old.pos().chunk());
        cache.replaceChunk(old.pos().chunk(), List.of(other));
        assertNull(cache.get(old.pos()));
        assertNotNull(cache.get(other.pos()));
        cache.replaceChunk(other.pos().chunk(), List.of());
        assertEquals(0, cache.size());
        assertEquals(0, cache.chunkCount());
        cache.put(old);
        cache.retainChunks(chunk -> chunk.x() == 0);
        assertEquals(0, cache.size());
    }

    @Test void dimensionAndSessionIdentityChangesInvalidateTheWholeCache() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        Object world = new Object();
        cache.scope(world, "nether");
        cache.put(entry(1, 20, 1, false));
        cache.scope(world, "nether");
        assertEquals(1, cache.size());
        cache.scope(world, "overworld");
        assertEquals(0, cache.size());
        cache.put(entry(1, 20, 1, false));
        cache.scope(new Object(), "overworld");
        assertEquals(0, cache.size());
        cache.put(entry(1, 20, 1, false));
        cache.scope(null, null);
        assertTrue(cache.snapshot().isEmpty());
    }

    @Test void snapshotsAreImmutableAndRevisionsChangeOnlyWithEvidence() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        var entry = entry(1, 20, 1, false);
        cache.put(entry);
        long revision = cache.revision();
        cache.put(entry);
        assertEquals(revision, cache.revision());
        var snapshot = cache.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        cache.remove(entry.pos());
        assertEquals(1, snapshot.size());
        assertTrue(cache.revision() > revision);
    }

    @Test void chunksEntriesAndPerChunkDensityAllHaveHardBounds() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        for (int i = 0; i < BlockEntityLeaks.MAX_CHUNKS + 7; i++) cache.put(entry(i * 16, 20, 0, false));
        assertEquals(BlockEntityLeaks.MAX_CHUNKS, cache.chunkCount());
        assertNull(cache.get(entry(0, 20, 0, false).pos()));
        cache.clear();
        for (int i = 0; i < BlockEntityLeaks.MAX_PER_CHUNK + 9; i++) cache.put(entry(i & 15, i >> 8, (i >> 4) & 15, false));
        assertEquals(BlockEntityLeaks.MAX_PER_CHUNK, cache.size());
        cache.clear();
        for (int chunk = 0; chunk < 20; chunk++) {
            for (int i = 0; i < BlockEntityLeaks.MAX_PER_CHUNK; i++) cache.put(entry(chunk * 16 + (i & 15), i >> 8, (i >> 4) & 15, false));
            assertTrue(cache.size() <= BlockEntityLeaks.MAX_ENTRIES);
        }
        assertEquals(BlockEntityLeaks.MAX_ENTRIES, cache.size());
    }

    @Test void invalidPositionsAndOversizedIdsCannotPopulateCache() {
        BlockEntityLeaks cache = new BlockEntityLeaks();
        for (var entry : List.of(entry(Integer.MIN_VALUE, 0, 0, false), entry(0, 2048, 0, false),
            entry(0, -2049, 0, false), entry(0, 0, 30_000_001, false))) cache.put(entry);
        cache.put(new BlockEntityLeaks.Entry(new BlockEntityLeaks.Position(0, 0, 0), "x".repeat(257), "minecraft:spawner", null, false));
        assertEquals(0, cache.size());
        assertEquals(0, cache.chunkCount());
        cache.put(entry(-30_000_000, -2048, 30_000_000, false));
        assertEquals(1, cache.size());
    }

    @Test void capturedSpawnerRangeTakesPrecedenceOverVanillaDefaults() {
        var server = new BlockEntityLeaks.Spawner("minecraft:skeleton", 32, 73);
        var client = new BlockEntityLeaks.Spawner("minecraft:zombie", 16, 71);
        var resolved = server.withLiveFallback(client);
        assertEquals("minecraft:skeleton", resolved.entityId());
        assertEquals(32, resolved.activationRange());
        assertEquals(71, resolved.delay());
        assertEquals(client, BlockEntityLeaks.Spawner.unknown().withLiveFallback(client));
    }

    @Test void spawnerDataValidatesUntrustedFieldsAndMatchesSphericalActivation() {
        var data = new BlockEntityLeaks.Spawner("minecraft:zombie", 16, 20);
        assertTrue(data.active(255.99));
        assertFalse(data.active(256));
        assertFalse(data.active(-1));
        assertFalse(data.active(Double.NaN));
        assertFalse(data.active(Double.POSITIVE_INFINITY));
        assertTrue(new BlockEntityLeaks.Spawner("minecraft:zombie", -1, -1).active(1_000_000));
        assertFalse(new BlockEntityLeaks.Spawner("minecraft:zombie", 0, 0).active(0));
        assertEquals(32767, new BlockEntityLeaks.Spawner("minecraft:zombie", 32767, 32767).activationRange());
        assertEquals(16, new BlockEntityLeaks.Spawner("minecraft:zombie", Integer.MAX_VALUE, 20).activationRange());
        assertEquals(-1, new BlockEntityLeaks.Spawner("minecraft:zombie", 16, Integer.MAX_VALUE).delay());
        assertEquals("", new BlockEntityLeaks.Spawner("not a registry id", 16, 20).entityId());
        assertEquals("", new BlockEntityLeaks.Spawner("minecraft:" + "x".repeat(256), 16, 20).entityId());
        assertEquals("", new BlockEntityLeaks.Spawner(null, 16, 20).entityId());
    }
}
