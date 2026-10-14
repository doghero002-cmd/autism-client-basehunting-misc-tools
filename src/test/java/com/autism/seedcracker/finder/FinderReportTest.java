package com.autism.seedcracker.finder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinderReportTest {
    @Test void aLargeFlagSetCannotStarveLaterChunks() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = new LinkedHashSet<ChunkPos>();
        for (int chunk = 0; chunk < 150; chunk++) flagged.add(new ChunkPos(chunk, 0));
        var seen = new java.util.HashMap<ChunkPos, Integer>();
        for (int tick = 0; tick < 300; tick++) report.tick(world, flagged, pos -> true,
            pos -> { seen.merge(pos, 1, Integer::sum); return 25; }, (pos, value) -> {});
        assertEquals(150, seen.size());
        assertTrue(seen.values().stream().allMatch(count -> count == 2));
    }

    @Test void refreshesChangedScoresAndNeverReportsUnloadedEvidence() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = Set.of(new ChunkPos(0, 0));
        var score = new AtomicInteger(90);
        var output = new ArrayList<Integer>();
        for (int tick = 0; tick < 20; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(90, output.getLast());
        score.set(20);
        for (int tick = 0; tick < 100; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(20, output.getLast());
        score.set(85);
        for (int tick = 0; tick < 100; tick++) report.tick(world, flagged, pos -> true, pos -> score.get(), (pos, value) -> output.add(value));
        assertEquals(85, output.getLast());
        output.clear();
        for (int tick = 0; tick < 40; tick++) report.tick(world, flagged, pos -> false, pos -> fail("Unloaded chunk scored"), (pos, value) -> output.add(value));
        assertTrue(output.isEmpty());
    }

    @Test void scansAtMostOneChunkPerTickAndResetsOnEmptyOrWorldChange() {
        var report = new FinderReport("Fixture", 10);
        var world = new Object();
        var flagged = new LinkedHashSet<ChunkPos>();
        for (int chunk = 0; chunk < 30; chunk++) flagged.add(new ChunkPos(chunk, 0));
        var scans = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            int before = scans.get();
            report.tick(world, flagged, pos -> true, pos -> { scans.incrementAndGet(); return 90; }, (pos, value) -> {});
            assertEquals(1, scans.get() - before);
        }
        report.tick(world, Set.of(), pos -> true, pos -> 90, (pos, value) -> fail("Empty results reported"));
        var output = new ArrayList<Integer>();
        var single = Set.of(new ChunkPos(0, 0));
        for (int tick = 0; tick < 20; tick++) report.tick(world, single, pos -> true, pos -> 25, (pos, value) -> output.add(value));
        assertEquals(25, output.getLast());
        output.clear();
        var nextWorld = new Object();
        for (int tick = 0; tick < 20; tick++) report.tick(nextWorld, single, pos -> true, pos -> 15, (pos, value) -> output.add(value));
        assertEquals(15, output.getLast());
    }
}