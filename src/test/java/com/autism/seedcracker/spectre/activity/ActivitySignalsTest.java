package com.autism.seedcracker.spectre.activity;

import com.autism.seedcracker.finder.UndergroundLight;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivitySignalsTest {
    private static BitSet mask(int... indices) {
        BitSet mask = new BitSet();
        for (int index : indices) mask.set(index);
        return mask;
    }
    private static boolean zero(PlayerLightTracker tracker, int x, int z, boolean underground) {
        return tracker.accept(x, z, 24, -4, mask(1), new BitSet(), List.of(new byte[2048]), underground);
    }

    @Test void onlyExplicitCompleteZeroSkyBelowCeilingIsEvidence() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        assertFalse(tracker.accept(0, 0, 24, -4, new BitSet(), mask(1), List.of(), false));
        assertFalse(tracker.accept(0, 0, 24, -4, mask(1), new BitSet(), List.of(new byte[2047]), false));
        assertFalse(tracker.accept(0, 0, 24, -4, mask(1), new BitSet(), List.of(), false));
        byte[] fullSky = new byte[2048];
        Arrays.fill(fullSky, (byte) 0xff);
        assertFalse(tracker.accept(0, 0, 24, -4, mask(1), new BitSet(), List.of(fullSky), false));
        assertFalse(tracker.accept(0, 0, 24, -4, mask(8), new BitSet(), List.of(new byte[2048]), false)); // top Y=63
        assertFalse(tracker.accept(0, 0, 24, -4, mask(0, 25), new BitSet(), List.of(new byte[2048], new byte[2048]), false));
        assertTrue(tracker.accept(0, 0, 24, -4, mask(7), new BitSet(), List.of(new byte[2048]), false)); // top Y=47
        assertTrue(tracker.isFlagged(QuantumRules.key(0, 0)));
    }

    @Test void sparseMasksKeepPayloadOrderAndPresentWinsOverEmpty() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        byte[] normal = new byte[2048];
        normal[0] = 0x12;
        assertTrue(tracker.accept(-2, 3, 24, -4, mask(0, 2, 30), mask(2),
            List.of(normal, new byte[2048], normal), false));
        assertEquals(Set.of(QuantumRules.key(-2, 3)), tracker.flagged());
    }

    @Test void spottedSignalsAreStickyUntilUnloadButDoNotNotifyTwice() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        assertTrue(zero(tracker, 5, -9, false));
        assertFalse(zero(tracker, 5, -9, false));
        assertFalse(tracker.accept(5, -9, 24, -4, new BitSet(), mask(1), List.of(), false));
        assertTrue(tracker.isFlagged(QuantumRules.key(5, -9)));
        tracker.forget(QuantumRules.key(5, -9));
        assertTrue(tracker.flagged().isEmpty());
        assertTrue(zero(tracker, 5, -9, false));
        tracker.clear();
        assertTrue(tracker.flagged().isEmpty());
    }

    @Test void undergroundPacketsAreHeldAndDoNotProtectAwayExistingEvidence() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        assertFalse(zero(tracker, 0, 0, true));
        tracker.updateEntry(0, 0, ignored -> true);
        assertTrue(tracker.flagged().isEmpty());
        assertTrue(zero(tracker, 0, 0, false));
    }

    @Test void enteringCleanChunksProtectsTheLoadedAndLateArrivingTwoChunkArea() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        long center = QuantumRules.key(0, 0);
        tracker.updateEntry(0, 0, key -> key == center);
        assertFalse(zero(tracker, 0, 0, false));
        tracker.updateEntry(0, 0, ignored -> true);
        assertFalse(zero(tracker, 2, -2, false));
        assertTrue(zero(tracker, 3, -2, false));
        tracker.forget(center);
        assertTrue(zero(tracker, 0, 0, false));
    }

    @Test void flaggedNeighboursAreNotProtectedAndUnloadedEntriesArePruned() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        assertFalse(zero(tracker, 1, 0, true));
        tracker.updateEntry(0, 0, ignored -> true);
        assertTrue(zero(tracker, 1, 0, false));
        tracker.retain(ignored -> false);
        assertTrue(tracker.flagged().isEmpty());
        assertTrue(zero(tracker, 0, 0, false));
    }

    @Test void rawEvidenceCacheHasAHardLimit() {
        PlayerLightTracker tracker = new PlayerLightTracker();
        for (int index = 0; index <= PlayerLightTracker.MAX_CHUNKS; index++) assertTrue(zero(tracker, index, 0, false));
        assertEquals(PlayerLightTracker.MAX_CHUNKS, tracker.flagged().size());
        assertFalse(tracker.isFlagged(QuantumRules.key(0, 0)));
    }

    @Test void quantumRangeUsesTheOriginalCutCornersAndSignedChunkKeys() {
        assertEquals(265, QuantumRules.scanRange(-20, 31).size());
        assertTrue(QuantumRules.inScanRange(-12, 36, -20, 31));
        assertFalse(QuantumRules.inScanRange(-12, 37, -20, 31));
        assertTrue(QuantumRules.inScanRange(-15, 39, -20, 31));
        assertFalse(QuantumRules.inScanRange(-14, 39, -20, 31));
        assertFalse(QuantumRules.inScanRange(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        long key = QuantumRules.key(-987, -654);
        assertEquals(-987, QuantumRules.x(key));
        assertEquals(-654, QuantumRules.z(key));
        assertEquals(new ChunkPos(-987, -654).pack(), key);
    }

    @Test void quantumIntersectsCandidateAreasButRestartsWhenSignalsDoNotOverlap() {
        Set<Long> first = QuantumRules.narrow(Set.of(), 0, 0);
        assertEquals(361, first.size());
        Set<Long> overlap = QuantumRules.narrow(first, 10, 0);
        assertEquals(171, overlap.size());
        assertTrue(overlap.contains(QuantumRules.key(1, 9)));
        assertFalse(overlap.contains(QuantumRules.key(0, 0)));
        Set<Long> restarted = QuantumRules.narrow(overlap, 40, -40);
        assertEquals(361, restarted.size());
        assertTrue(restarted.contains(QuantumRules.key(40, -40)));
    }

    @Test void quantumKeepsTheAsymmetricOriginalBlockExclusions() {
        assertTrue(QuantumRules.ignoredChange("minecraft:stone", "minecraft:chest"));
        assertTrue(QuantumRules.ignoredChange("minecraft:amethyst_block", "minecraft:air"));
        assertTrue(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:deepslate_diamond_ore"));
        assertTrue(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:observer"));
        assertFalse(QuantumRules.ignoredChange("minecraft:dirt", "minecraft:chest"));
        assertFalse(QuantumRules.ignoredChange("minecraft:observer", "minecraft:air"));
        assertFalse(QuantumRules.ignoredChange("minecraft:dirt", "example:stone"));
    }

    @Test void lightRequiresBlockLightToBeatSkyAndKeepsSourceDenoising() {
        assertTrue(LightSnapshot.qualifies(5, 4, 5));
        assertFalse(LightSnapshot.qualifies(5, 5, 5));
        assertFalse(LightSnapshot.qualifies(14, 15, 5));
        assertFalse(LightSnapshot.qualifies(4, 0, 5));
        BlockPos weak = new BlockPos(0, -20, 0);
        assertTrue(UndergroundLight.surface(Map.of(weak, 6, weak.below(), 5), true, 5).isEmpty());
        assertEquals(2, UndergroundLight.surface(Map.of(weak, 6, weak.above(), 14), true, 5).values().stream().mapToInt(Set::size).sum());
        LightSnapshot snapshot = new LightSnapshot(new ChunkPos(0, 0), 8, 5);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.hits().put(weak, 15));
        assertThrows(IllegalStateException.class, () -> snapshot.collect(null, 16));
    }

    @Test void unloadedChunksCannotReappearFromAnInFlightLightResult() {
        BlockPos removed = new BlockPos(-1, -20, -1);
        BlockPos kept = new BlockPos(16, -20, 16);
        Map<Integer, Set<BlockPos>> old = Map.of(7, Set.of(removed), 14, Set.of(removed, kept));
        Map<Integer, Set<BlockPos>> fresh = LightSnapshot.exclude(old, Set.of(new ChunkPos(-1, -1)));
        assertEquals(Map.of(14, Set.of(kept)), fresh);
        assertEquals(2, old.get(14).size());
        assertThrows(UnsupportedOperationException.class, () -> fresh.get(14).add(removed));
    }

    @Test void lightGradientPreservesFillAlphaAndUsesDarkOpaqueOutlines() {
        assertEquals(0x80ffffff, ActivityRender.lightFill(0x80ffcc00, 15));
        int dim = ActivityRender.lightFill(0x80ffcc00, 5);
        assertEquals(85, dim >>> 24);
        assertEquals(0xff, ActivityRender.lightLine(dim) >>> 24);
        assertEquals(0xff737373, ActivityRender.lightLine(0x80ffffff));
        assertEquals(0x00ffffff, ActivityRender.lightFill(0x00ffcc00, 15));
    }
}
