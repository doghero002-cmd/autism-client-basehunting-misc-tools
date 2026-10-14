package com.autism.seedcracker.finder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinedGeodeTrackerTest {
    @Test void nearbyCellsJoinAcrossChunkBoundariesAndBuddingThresholdIsExact() {
        Set<BlockPos> lining = new HashSet<>();
        for (int index = 0; index < 10; index++) lining.add(new BlockPos(-5 + index, -20, 0));
        var clusters = MinedGeodeTracker.clusters(List.of(new MinedGeodeTracker.Slice(lining, lining, 0)));
        assertEquals(1, clusters.size()); assertTrue(clusters.getFirst().large());
        assertEquals(-1, clusters.getFirst().chunk().x());
        Set<BlockPos> fewerBuds = new HashSet<>(lining); fewerBuds.remove(fewerBuds.iterator().next());
        assertFalse(MinedGeodeTracker.clusters(List.of(new MinedGeodeTracker.Slice(lining, fewerBuds, 0))).getFirst().large());
    }

    @Test void sourceWalkFindsTorchPeakAndRejectsUnknownNeighbors() {
        BlockPos start = new BlockPos(0, -20, 0);
        assertEquals(14, MinedGeodeTracker.peak(start, 10, pos -> pos.getY() == -20 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 4 ? 10 + pos.getX() : 0, new HashMap<>()));
        assertEquals(15, MinedGeodeTracker.peak(start, 15, pos -> 15, new HashMap<>()));
        assertEquals(-1, MinedGeodeTracker.peak(start, 1, pos -> -1, new HashMap<>()));
    }
}