package com.autism.seedcracker.util.pure;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.util.pure.SusScore.Signal;

import static org.junit.jupiter.api.Assertions.*;

class SusScoreTest {

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
}
