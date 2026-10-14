package com.autism.seedcracker.finder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkFlagRendererTest {
    @AfterEach void reset() {
        ChunkFlagRenderer.clear("fixture-a");
        ChunkFlagRenderer.clear("fixture-b");
        ChunkFlagRenderer.configureDisplayY(true, 16);
        ChunkFlagRenderer.configureSmartOverlap(false, 2, 9);
    }

    @Test void markerHeightStaysFixedAndClampsToTheDimension() {
        ChunkFlagRenderer.configureDisplayY(true, 64);
        assertEquals(64, ChunkFlagRenderer.markerY(-64, 320));
        ChunkFlagRenderer.configureDisplayY(false, 0);
        assertEquals(64, ChunkFlagRenderer.markerY(-64, 320));
        ChunkFlagRenderer.configureDisplayY(true, 320);
        assertEquals(127, ChunkFlagRenderer.markerY(0, 128));
        ChunkFlagRenderer.configureDisplayY(true, -100);
        assertEquals(-64, ChunkFlagRenderer.markerY(-64, 320));
    }

    @Test void twoSourcesAgreeEvenBelowTheSingleSourceFloodThreshold() {
        var pos = new ChunkPos(0, 0);
        ChunkFlagRenderer.feed("fixture-a", Set.of(pos), 1, false);
        ChunkFlagRenderer.feed("fixture-b", Set.of(pos), 2, false);
        assertEquals(Set.of(pos), ChunkFlagRenderer.analyzeOverlap().hotspots());
    }

    @Test void densityPeakAndLocalColourIgnoreDistantFlags() {
        var positions = new HashSet<ChunkPos>();
        for (int chunkX = 0; chunkX < 5; chunkX++) for (int chunkZ = 0; chunkZ < 5; chunkZ++) positions.add(new ChunkPos(chunkX, chunkZ));
        ChunkFlagRenderer.feed("fixture-a", positions, 7, false);
        assertEquals(Set.of(new ChunkPos(2, 2)), ChunkFlagRenderer.analyzeOverlap().hotspots());
        var result = new ChunkFlagRenderer.OverlapResult(Set.of(), Set.of(), Map.of(
            new ChunkPos(0, 0), 7, new ChunkPos(1, 0), 7, new ChunkPos(100, 100), 9));
        assertEquals(7, result.dominantColor(new ChunkPos(0, 0), 0));
        assertEquals(0, result.dominantColor(new ChunkPos(50, 50), 0));
    }
}