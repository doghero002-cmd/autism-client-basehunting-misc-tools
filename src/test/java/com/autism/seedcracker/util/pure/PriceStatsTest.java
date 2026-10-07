package com.autism.seedcracker.util.pure;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.util.pure.PriceStats.Sample;
import com.autism.seedcracker.util.pure.PriceStats.Summary;

import static org.junit.jupiter.api.Assertions.*;

class PriceStatsTest {

    private static final long NOW = 1_000_000_000L;
    private static final long DAY = 24L * 3600_000;

    private static Sample at(double price, long ageMs) {
        return new Sample(price, NOW - ageMs);
    }

    // ---- summarize ----

    @Test
    void emptyIsNull() {
        assertNull(PriceStats.summarize(List.of(), NOW, DAY));
        assertNull(PriceStats.summarize(null, NOW, DAY));
    }

    @Test
    void basicStats() {
        Summary s = PriceStats.summarize(List.of(at(10, 0), at(20, 0), at(30, 0)), NOW, DAY);
        assertEquals(3, s.samples());
        assertEquals(10, s.min());
        assertEquals(30, s.max());
        assertEquals(20, s.median());
        assertEquals(20, s.mean(), 1e-9);
    }

    @Test
    void recentMedianUsesWindowOnly() {
        // Old samples at 100, fresh samples at 10: recent median must ignore the old ones.
        Summary s = PriceStats.summarize(List.of(
            at(100, 2 * DAY), at(100, 2 * DAY), at(10, 0), at(12, 0), at(14, 0)), NOW, DAY);
        assertEquals(12, s.recentMedian());
        assertEquals(14, s.median()); // all-time median includes the old 100s
    }

    @Test
    void recentMedianFallsBackToAllTime() {
        // Nothing inside the window: fall back instead of reporting 0 (would nuke dealScore).
        Summary s = PriceStats.summarize(List.of(at(50, 2 * DAY), at(70, 3 * DAY)), NOW, DAY);
        assertEquals(s.median(), s.recentMedian());
    }

    // ---- dealScore ----

    @Test
    void dealScoreFractionUnderMedian() {
        assertEquals(0.5, PriceStats.dealScore(50, 100), 1e-9);  // half price = 50% deal
        assertEquals(0.0, PriceStats.dealScore(100, 100), 1e-9); // at market
        assertEquals(-0.2, PriceStats.dealScore(120, 100), 1e-9); // 20% OVER market
    }

    @Test
    void dealScoreUnknownMedianIsNaN() {
        assertTrue(Double.isNaN(PriceStats.dealScore(50, 0)));
        assertTrue(Double.isNaN(PriceStats.dealScore(50, -1)));
        assertTrue(Double.isNaN(PriceStats.dealScore(50, Double.NaN)));
    }

    // ---- isDuplicateListing ----

    @Test
    void samePriceInWindowIsDuplicate() {
        List<Sample> existing = List.of(at(500, 10_000)); // 10s ago
        assertTrue(PriceStats.isDuplicateListing(existing, 500, NOW, 60_000, 30));
    }

    @Test
    void samePriceOutsideWindowIsNewData() {
        List<Sample> existing = List.of(at(500, 120_000)); // 2 min ago
        assertFalse(PriceStats.isDuplicateListing(existing, 500, NOW, 60_000, 30));
    }

    @Test
    void differentPriceIsNewData() {
        List<Sample> existing = List.of(at(500, 10_000));
        assertFalse(PriceStats.isDuplicateListing(existing, 450, NOW, 60_000, 30));
    }

    @Test
    void lookbackBoundsTheScan() {
        // Duplicate exists but beyond the lookback depth: treated as new (bounded work per record).
        List<Sample> existing = List.of(at(500, 5000), at(1, 4000), at(2, 3000), at(3, 2000));
        assertFalse(PriceStats.isDuplicateListing(existing, 500, NOW, 60_000, 3));
    }

    // ---- normalizeItemId ----

    @Test
    void normalizeStripsPrefixAndCase() {
        assertEquals("oak_log", PriceStats.normalizeItemId("minecraft:oak_log"));
        assertEquals("oak_log", PriceStats.normalizeItemId("  OAK_LOG  "));
        assertEquals("mod:thing", PriceStats.normalizeItemId("mod:thing")); // non-mc prefix kept
    }

    // ---- percentile ----

    @Test
    void percentileNearestRank() {
        assertEquals(2.0, PriceStats.percentile(List.of(1.0, 2.0, 3.0), 0.5));
        assertEquals(1.0, PriceStats.percentile(List.of(1.0, 2.0, 3.0), 0.0));
        assertEquals(3.0, PriceStats.percentile(List.of(1.0, 2.0, 3.0), 1.0));
        assertEquals(0.0, PriceStats.percentile(List.of(), 0.5));
    }
}
