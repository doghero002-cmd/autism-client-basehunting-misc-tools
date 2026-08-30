package com.example.donutflipscanner.data;

import java.util.List;
import java.util.Objects;

/** Immutable display snapshot consumed by the dashboard chart. */
public record MarketChartSnapshot(
        String title,
        String rangeLabel,
        long currentValue,
        double changePercent,
        List<MarketChartPoint> points
) {
    public MarketChartSnapshot {
        title = Objects.requireNonNull(title, "title");
        rangeLabel = Objects.requireNonNull(rangeLabel, "rangeLabel");
        points = List.copyOf(Objects.requireNonNull(points, "points"));
    }

    public static MarketChartSnapshot unavailable() {
        return new MarketChartSnapshot("MARKET INDEX", "NO DATA", 0L, 0.0D, List.of());
    }
}
