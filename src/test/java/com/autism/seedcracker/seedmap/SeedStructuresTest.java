package com.autism.seedcracker.seedmap;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.seedmap.SeedStructures.Hit;
import com.autism.seedcracker.seedmap.SeedStructures.Type;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.Village;

import static org.junit.jupiter.api.Assertions.*;

class SeedStructuresTest {

    private static final long SEED = 123456789L;

    @Test
    void nearestVillagesAreSortedRealAndConsistentWithTheLibrary() {
        SeedStructures s = new SeedStructures(SEED, MCVersion.v1_21);
        List<Hit> hits = s.nearest(List.of(Type.VILLAGE), Dimension.OVERWORLD, 0, 0, 3, 8);
        assertFalse(hits.isEmpty(), "a normal seed has villages within 8 regions");
        for (int i = 1; i < hits.size(); i++) assertTrue(hits.get(i - 1).dist() <= hits.get(i).dist());
        // Every hit is exactly the library's start chunk for its region (centre of chunk).
        Village v = new Village(MCVersion.v1_21);
        int spacing = v.getSpacing() * 16;
        for (Hit h : hits) {
            int rx = Math.floorDiv(h.blockX(), spacing), rz = Math.floorDiv(h.blockZ(), spacing);
            CPos c = v.getInRegion(SEED, rx, rz, new ChunkRand());
            assertEquals((c.getX() << 4) + 8, h.blockX());
            assertEquals((c.getZ() << 4) + 8, h.blockZ());
        }
    }

    @Test
    void wrongDimensionTypesAreIgnoredAndParseIsForgiving() {
        SeedStructures s = new SeedStructures(SEED, MCVersion.v1_21);
        assertTrue(s.nearest(List.of(Type.FORTRESS), Dimension.OVERWORLD, 0, 0, 1, 2).isEmpty());
        assertEquals(List.of(Type.VILLAGE, Type.END_CITY, Type.DESERT_TEMPLE), SeedStructures.parse("Village, end_city,desert temple,bogus"));
    }

    @Test
    void strongholdsComeFromTheRings() {
        SeedStructures s = new SeedStructures(SEED, MCVersion.v1_21);
        List<Hit> hits = s.nearest(List.of(Type.STRONGHOLD), Dimension.OVERWORLD, 0, 0, 3, 1);
        assertEquals(3, hits.size());
        // First ring sits 1280-2816 blocks out.
        assertTrue(hits.get(0).dist() > 1000 && hits.get(0).dist() < 3200);
    }
}
