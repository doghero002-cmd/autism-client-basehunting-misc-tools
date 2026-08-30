package com.example.donutflipscanner.data;

import java.util.Objects;

public record MarketChartPoint(String label, long value) {
    public MarketChartPoint {
        label = Objects.requireNonNull(label, "label");
    }
}
