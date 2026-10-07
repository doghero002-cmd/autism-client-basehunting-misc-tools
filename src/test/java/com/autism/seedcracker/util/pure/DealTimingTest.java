package com.autism.seedcracker.util.pure;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DealTimingTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static PriceStats.Sample at(int hour, double price) {
        long ms = LocalDateTime.of(2026, 1, 1, hour, 30).toInstant(ZoneOffset.UTC).toEpochMilli();
        return new PriceStats.Sample(price, ms);
    }

    @Test
    void bucketsByHour() {
        DealTiming.HourStat[] h = DealTiming.byHour(List.of(at(3, 10), at(3, 20), at(3, 30), at(15, 100)), UTC);
        assertEquals(24, h.length);
        assertEquals(3, h[3].samples());
        assertEquals(20, h[3].median());
        assertEquals(1, h[15].samples());
        assertEquals(0, h[0].samples());
        assertTrue(Double.isNaN(h[0].median()));
    }

    @Test
    void cheapestHourRespectsMinSamples() {
        List<PriceStats.Sample> s = new ArrayList<>();
        s.add(at(2, 1)); // single very cheap sample: not enough evidence
        for (int i = 0; i < 5; i++) s.add(at(4, 50));
        for (int i = 0; i < 5; i++) s.add(at(18, 90));
        DealTiming.HourStat[] h = DealTiming.byHour(s, UTC);
        assertEquals(4, DealTiming.cheapestHour(h, 3));
        assertEquals(2, DealTiming.cheapestHour(h, 1));
    }

    @Test
    void noQualifyingHourIsMinusOne() {
        DealTiming.HourStat[] h = DealTiming.byHour(List.of(at(5, 10)), UTC);
        assertEquals(-1, DealTiming.cheapestHour(h, 3));
        assertEquals(-1, DealTiming.cheapestHour(DealTiming.byHour(List.of(), UTC), 1));
    }

    @Test
    void hourDiscountFraction() {
        DealTiming.HourStat cheap = new DealTiming.HourStat(4, 5, 80);
        assertEquals(0.2, DealTiming.hourDiscount(cheap, 100), 1e-9);
        assertTrue(Double.isNaN(DealTiming.hourDiscount(new DealTiming.HourStat(1, 0, Double.NaN), 100)));
        assertTrue(Double.isNaN(DealTiming.hourDiscount(cheap, 0)));
    }
}
