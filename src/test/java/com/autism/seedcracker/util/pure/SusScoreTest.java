package com.autism.seedcracker.util.pure;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.util.pure.SusScore.Signal;

import static org.junit.jupiter.api.Assertions.*;

class SusScoreTest {
    @Test
    void equalSpreadHeatKeepsTheMarkerOnTheDirectEvidenceChunk() {
        assertTrue(SusScore.geodeTiePreferred(6, 100, 0, 1));
        assertFalse(SusScore.geodeTiePreferred(0, 1, 6, 100));
        assertTrue(SusScore.geodeTiePreferred(6, 1, 6, 100));
        assertFalse(SusScore.geodeTiePreferred(6, 100, 6, 1));
        assertFalse(SusScore.geodeTiePreferred(6, 100, 6, 100));
    }

    @Test
    void emptyTerrainUsesTheReferenceLowerHeatThreshold() {
        assertTrue(SusScore.geodePeakEligible(1, false, 6, 0));
        assertFalse(SusScore.geodePeakEligible(1, true, 6, 0));
        assertFalse(SusScore.geodePeakEligible(0, false, 6, 0));
        assertTrue(SusScore.geodePeakEligible(6, true, 6, 0));
    }

    @Test
    void strongerEligibleNeighboursSuppressPeaksBeforeTheirOwnThresholdIsApplied() {
        assertFalse(SusScore.geodePeakEligible(2, false, 6, 3));
        assertTrue(SusScore.geodePeakEligible(6, true, 6, 6));
        assertFalse(SusScore.geodePeakEligible(6, true, 6, 7));
    }

    @Test
    void glowRequiresADarkCellWithAnOpenFourLightNeighbour() {
        int[] light = {4, 0, 0, 0, 0, 0};
        boolean[] open = {true, false, false, false, false, false};
        assertTrue(SusScore.glowEdge(0, light, open));
        assertFalse(SusScore.glowEdge(1, light, open));
        assertFalse(SusScore.glowEdge(0, light, new boolean[6]));
        assertFalse(SusScore.glowEdge(0, new int[]{4, 5, 0, 0, 0, 0}, open));
        assertFalse(SusScore.glowEdge(0, new int[]{3, 0, 0, 0, 0, 0}, open));
    }


    @Test
    void oneStrongSignalAloneNeverFlags() {
        SusScore.Result r = SusScore.score(Map.of(Signal.STORAGE, 1.0), 100, 40, 2);
        assertTrue(r.score() >= 40);
        assertFalse(r.flagged(), "storage alone could be a dungeon chest");
    }

    @Test
    void twoIndependentSignalsFlag() {
        SusScore.Result r = SusScore.score(Map.of(Signal.ROTATED_DEEPSLATE, 1.0, Signal.FLAT_ROOM, 1.0), 0, 40, 2);
        assertTrue(r.flagged());
        assertEquals(2, r.signals());
        assertTrue(r.why().startsWith("rotated deepslate"));
    }

    @Test
    void weakPartialSignalsDontCountTowardAgreement() {
        SusScore.Result r = SusScore.score(Map.of(Signal.GLOW, 0.3, Signal.SKULL_CANDLE, 0.3, Signal.STORAGE, 1.0), 0, 40, 2);
        assertEquals(1, r.signals());
        assertFalse(r.flagged());
    }

    @Test
    void naturalGlowAndOverlappingBuiltScoreCannotSupplyAgreement() {
        SusScore.Result result = SusScore.score(Map.of(Signal.STORAGE, 1.0,
            Signal.GLOW, 1.0, Signal.BUILT, 1.0), 100, 45, 2);
        assertEquals(1, result.signals());
        assertFalse(result.flagged());
        assertFalse(SusScore.score(Map.of(Signal.GLOW, 1.0, Signal.FLAT_ROOM, 1.0), 100, 45, 2).flagged());
        assertTrue(SusScore.score(Map.of(Signal.STORAGE, 1.0, Signal.POWERED_REDSTONE, 0.5), 0, 45, 2).flagged());
    }

    @Test
    void vegetationIsOneEvidenceFamilyNotTwoBaseDetectors() {
        SusScore.Result result = SusScore.score(Map.of(Signal.DEEP_VINES, 1.0, Signal.DEEP_KELP, 1.0), 100, 45, 2);
        assertEquals(1, result.signals());
        assertFalse(result.flagged());
    }

    @Test
    void caveShapeAndVegetationCannotConfirmABaseWithoutConstructionEvidence() {
        SusScore.Result result = SusScore.score(Map.of(Signal.FLAT_ROOM, 1.0,
            Signal.DEEP_VINES, 1.0, Signal.DEEP_KELP, 1.0, Signal.GLOW, 1.0), 100, 45, 2);
        assertTrue(result.score() >= 45);
        assertFalse(result.flagged());
    }

    @Test
    void correlatedEvidenceCannotInflateTheConfirmationScore() {
        SusScore.Result baseline = SusScore.score(Map.of(Signal.STORAGE, 0.5, Signal.DEEP_VINES, 1.0), 0, 60, 2);
        SusScore.Result duplicated = SusScore.score(Map.of(Signal.STORAGE, 0.5,
            Signal.DEEP_VINES, 1.0, Signal.DEEP_KELP, 1.0), 0, 60, 2);
        assertEquals(baseline.score(), duplicated.score());
        assertEquals(baseline.signals(), duplicated.signals());
        assertFalse(duplicated.flagged());
    }

    @Test
    void realConstructionStillFlagsWithIndependentCorroboration() {
        assertTrue(SusScore.score(Map.of(Signal.STORAGE, 0.5, Signal.FLAT_ROOM, 1.0), 20, 45, 2).flagged());
        assertTrue(SusScore.score(Map.of(Signal.POWERED_REDSTONE, 1.0, Signal.FLAT_ROOM, 1.0), 0, 45, 2).flagged());
        assertTrue(SusScore.score(Map.of(Signal.ROTATED_DEEPSLATE, 1.0, Signal.DEEP_KELP, 1.0), 0, 45, 2).flagged());
        assertFalse(SusScore.score(Map.of(Signal.STORAGE, 0.3, Signal.FLAT_ROOM, 1.0,
            Signal.DEEP_VINES, 1.0), 100, 45, 2).flagged());
    }

    @Test
    void neighbourHeatOnlyTopsUpExistingEvidence() {
        assertEquals(0, SusScore.score(Map.of(), 100, 10, 1).score());
        int base = SusScore.score(Map.of(Signal.DEEP_KELP, 1.0), 0, 10, 1).score();
        int spread = SusScore.score(Map.of(Signal.DEEP_KELP, 1.0), 100, 10, 1).score();
        assertEquals(base + 15, spread);
    }

    @Test
    void scoreIsCappedAt100() {
        Map<Signal, Double> all = new java.util.EnumMap<>(Signal.class);
        for (Signal s : Signal.values()) all.put(s, 1.0);
        assertEquals(100, SusScore.score(all, 100, 40, 2).score());
    }

    @Test
    void rampScalesFromThreshold() {
        assertEquals(0, SusScore.ramp(2, 3));
        assertEquals(0.5, SusScore.ramp(3, 3), 1e-9);
        assertEquals(1.0, SusScore.ramp(6, 3), 1e-9);
        assertEquals(1.0, SusScore.ramp(100, 3), 1e-9);
    }

    @Test
    void ordinaryStorageMustBeClusteredAndOutsideNaturalStructures() {
        assertEquals(0, SusScore.storageStrength(0, 1, false));
        assertEquals(0, SusScore.storageStrength(0, 3, false));
        assertEquals(0.5, SusScore.storageStrength(0, 4, false));
        assertEquals(1.0, SusScore.storageStrength(0, 8, false));
        assertEquals(0, SusScore.storageStrength(0, 20, true));
        assertEquals(0.5, SusScore.storageStrength(1, 0, true));
    }

    @Test
    void chestOrBarrelBaseCanConfirmWithRunningRedstone() {
        double storage = SusScore.storageStrength(0, 4, false);
        SusScore.Result result = SusScore.score(Map.of(Signal.STORAGE, storage,
            Signal.POWERED_REDSTONE, 1.0), 0, 45, 2);
        assertTrue(result.flagged());
        assertEquals(2, result.signals());
    }

    @Test
    void structureStorageAndCaveRoomCannotConfirmABase() {
        double storage = SusScore.storageStrength(0, 8, true);
        assertFalse(SusScore.score(Map.of(Signal.STORAGE, storage,
            Signal.FLAT_ROOM, 1.0, Signal.GLOW, 1.0), 100, 45, 2).flagged());
    }

    @Test
    void flatFloorRequiresAnUnbrokenRoomNotScatteredCaveCells() {
        boolean[] cells = new boolean[256];
        for (int cell = 0; cell < cells.length; cell += 2) cells[cell] = true;
        assertFalse(SusScore.flatFloorHit(cells, 30));
        java.util.Arrays.fill(cells, false);
        for (int row = 4; row < 9; row++) {
            for (int column = 3; column < 9; column++) cells[row * 16 + column] = true;
        }
        assertTrue(SusScore.flatFloorHit(cells, 30));
        assertFalse(SusScore.flatFloorHit(cells, 31));
        cells[6 * 16 + 6] = false;
        assertFalse(SusScore.flatFloorHit(cells, 30));
    }

    @Test
    void narrowCorridorDoesNotCountAsARoom() {
        boolean[] cells = new boolean[256];
        java.util.Arrays.fill(cells, 0, 32, true);
        assertFalse(SusScore.flatFloorHit(cells, 30));
        assertThrows(IllegalArgumentException.class, () -> SusScore.flatFloorHit(new boolean[16], 10));
    }

    @Test
    void emptyChunksAreNotImmatureGeodeVetoes() {
        var growth = new SusScore.GeodeGrowth(0, 0, 0, 0);
        assertFalse(growth.veto());
        assertEquals(0, growth.heat());
    }

    @Test
    void attachedYoungBudsVetoEvenWhenMatureClustersExist() {
        var growth = new SusScore.GeodeGrowth(12, 0, 1, 0);
        assertTrue(growth.veto());
        assertEquals(0, growth.heat());
    }

    @Test
    void missingSupportChunksCannotBecomePlacedGrowthEvidence() {
        var growth = new SusScore.GeodeGrowth(5, 0, 0, 1);
        assertFalse(growth.complete());
        assertTrue(growth.veto());
        assertEquals(0, growth.heat());
    }

    @Test
    void matureAndDetachedGrowthContributeWithoutOverflow() {
        assertEquals(7, new SusScore.GeodeGrowth(5, 2, 0, 0).heat());
        assertEquals(Integer.MAX_VALUE, new SusScore.GeodeGrowth(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0).heat());
        assertTrue(new SusScore.GeodeGrowth(5, 2, 0, 0).complete());
        assertThrows(IllegalArgumentException.class, () -> new SusScore.GeodeGrowth(-1, 0, 0, 0));
    }

    @Test
    void baseClusterCannotCombineDistantStorageAndSpawners() {
        int[] counts = new int[81];
        boolean[] spawners = new boolean[81];
        counts[10] = 35;
        spawners[70] = true;
        assertEquals(-1, SusScore.findBaseCluster(counts, spawners, 9, 35, true));
        assertTrue(SusScore.findBaseCluster(counts, spawners, 9, 35, false) >= 0);
        spawners[11] = true;
        assertTrue(SusScore.findBaseCluster(counts, spawners, 9, 35, true) >= 0);
    }

    @Test
    void storageThresholdIsLocalAndWorksAcrossChunkBoundaries() {
        int[] counts = new int[81];
        boolean[] spawners = new boolean[81];
        counts[10] = 20;
        counts[70] = 20;
        assertEquals(-1, SusScore.findBaseCluster(counts, spawners, 9, 35, false));
        counts[11] = 15;
        assertTrue(SusScore.findBaseCluster(counts, spawners, 9, 35, false) >= 0);
        assertThrows(IllegalArgumentException.class, () -> SusScore.findBaseCluster(counts, spawners, 8, 35, false));
    }
}
