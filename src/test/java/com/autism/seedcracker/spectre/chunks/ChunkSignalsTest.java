package com.autism.seedcracker.spectre.chunks;

import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSignalsTest {
    @Test void boundedPeakPassesMatchDirectSquareSearchIncludingEdgesAndTies() {
        var random = new Random(71);
        int side = 7; int[] values = random.ints(side * side, 0, 8).toArray();
        for (int reach = 0; reach <= 3; reach++) {
            int[] actual = ChunkSignals.neighborhoodMax(values, side, reach);
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) {
                int expected = 0;
                for (int a = Math.max(0, x - reach); a <= Math.min(side - 1, x + reach); a++)
                    for (int b = Math.max(0, z - reach); b <= Math.min(side - 1, z + reach); b++) expected = Math.max(expected, values[a * side + b]);
                assertEquals(expected, actual[x * side + z]);
            }
        }
        assertArrayEquals(new int[]{5, 5, 5, 5}, ChunkSignals.neighborhoodMax(new int[]{5, 5, 0, 0}, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> ChunkSignals.neighborhoodMax(new int[4], 3, 1));
    }

    @Test void beeEvidenceIsBoundedAndDoesNotAssignPointsToPlantCounts() {
        assertEquals(0, ActivityClues.beeScore(0, 0));
        assertEquals(2, ActivityClues.beeScore(1, 0));
        assertEquals(4, ActivityClues.beeScore(0, 1));
        assertEquals(8, ActivityClues.beeScore(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ActivityClues.beeScore(-1, 0));
    }

    @Test void fullBaselinePartialUpdateAndExplicitEmptyHaveDifferentSemantics() {
        var history = new LightHistory(2);
        byte[] first = section(0x45), second = section(0x12);
        assertTrue(history.apply(1L, -4, 0, -5, mask(1, 3), mask(), List.of(first, second), true).isEmpty());
        first[0] = 0; // Packet storage must not remain aliased to the baseline.
        assertTrue(history.apply(1L, -4, 0, -5, mask(1), mask(), List.of(section(0x45)), false).isEmpty());
        var changes = history.apply(1L, -4, 0, -5, mask(), mask(1), List.of(), false);
        assertEquals(1, changes.size()); assertEquals(-4, changes.getFirst().sectionY());
        assertEquals(0x45, changes.getFirst().previous()[0]); assertEquals(0, changes.getFirst().incoming()[0]);
        assertTrue(history.apply(1L, -4, 0, -5, mask(3), mask(), List.of(section(0x12)), false).isEmpty());
        assertTrue(history.apply(1L, -4, 0, -5, mask(5), mask(), List.of(section(15)), false).isEmpty(), "Above-zero sections are not Prime evidence");
    }

    @Test void unknownOrMalformedHistoryNeverManufacturesALightChange() {
        var history = new LightHistory(2);
        assertTrue(history.apply(1L, -4, 0, -5, mask(1), mask(), List.of(section(5)), false).isEmpty());
        assertFalse(history.contains(1L));
        history.apply(1L, -4, 0, -5, mask(1), mask(), List.of(section(5)), true);
        assertTrue(history.apply(1L, -4, 0, -5, mask(1), mask(), List.of(new byte[2]), false).isEmpty());
        assertFalse(history.contains(1L));
        assertTrue(history.apply(2L, -4, 0, -5, mask(1, 2), mask(), List.of(section(2)), true).isEmpty());
        assertFalse(history.contains(2L));
    }

    @Test void cacheEvictionAndClearDoNotCarryBaselinesBetweenSessions() {
        var history = new LightHistory(2);
        for (long key = 1; key <= 3; key++) history.apply(key, -4, 0, -5, mask(), mask(), List.of(), true);
        assertEquals(2, history.size()); assertFalse(history.contains(1L)); assertTrue(history.contains(3L));
        history.forget(2L); assertEquals(1, history.size()); history.clear(); assertEquals(0, history.size());
    }

    private static byte[] section(int first) { var bytes = new byte[2048]; bytes[0] = (byte) first; return bytes; }
    private static BitSet mask(int... indices) { var mask = new BitSet(); for (int index : indices) mask.set(index); return mask; }
}
