package com.autism.seedcracker.util.pure;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerFinderSignalsTest {
    @Test void anInitialOrRepeatedStateDoesNotCountAsActivity() {
        assertFalse(PlayerFinderSignals.changed(null, "stone"));
        assertFalse(PlayerFinderSignals.changed("stone", null));
        assertFalse(PlayerFinderSignals.changed("stone", new String("stone")));
        assertTrue(PlayerFinderSignals.changed("stone", "air"));
    }
    @Test void onlyAnExplicitCompleteZeroPayloadIsDarkEvidence() {
        assertEquals(0, PlayerFinderSignals.classify(null));
        assertEquals(0, PlayerFinderSignals.classify(new byte[2047]));
        assertEquals(1, PlayerFinderSignals.classify(new byte[2048]));
        byte[] normal = new byte[2048];
        normal[100] = 1;
        assertEquals(2, PlayerFinderSignals.classify(normal));
    }
    @Test void partialUpdatesRetainHistoryButEmptyMasksRemoveDarkEvidence() {
        byte[] sections = new byte[6];
        BitSet present = new BitSet();
        present.set(1);
        PlayerFinderSignals.apply(sections, present, new BitSet(), List.of(new byte[2048]));
        assertTrue(PlayerFinderSignals.darkSignal(sections, 0));
        PlayerFinderSignals.apply(sections, new BitSet(), new BitSet(), List.of());
        assertTrue(PlayerFinderSignals.darkSignal(sections, 0));
        PlayerFinderSignals.apply(sections, new BitSet(), present, List.of());
        assertFalse(PlayerFinderSignals.darkSignal(sections, 0));
    }
    @Test void missingPayloadAndAboveCeilingSectionsAreNotEvidence() {
        byte[] sections = new byte[6];
        BitSet present = new BitSet();
        present.set(1);
        PlayerFinderSignals.apply(sections, present, new BitSet(), List.of());
        assertFalse(PlayerFinderSignals.darkSignal(sections, 0));
        PlayerFinderSignals.apply(sections, present, new BitSet(), List.of(new byte[2048]));
        assertFalse(PlayerFinderSignals.darkSignal(sections, 4));
    }
    @Test void simulationBoundaryIncludesAnExtraChunkAndFailsClosedWhenUnknown() {
        assertFalse(PlayerFinderSignals.outsideOwnSimulation(50, 0));
        assertFalse(PlayerFinderSignals.outsideOwnSimulation(9, 8));
        assertTrue(PlayerFinderSignals.outsideOwnSimulation(10, 8));
    }
    @Test void expiryAndClockRollbackDoNotKeepSignals() {
        assertTrue(PlayerFinderSignals.active(100, 149, 50));
        assertFalse(PlayerFinderSignals.active(100, 150, 50));
        assertFalse(PlayerFinderSignals.active(100, 99, 50));
    }
}