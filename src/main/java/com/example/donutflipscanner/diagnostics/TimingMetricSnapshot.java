package com.example.donutflipscanner.diagnostics;

import java.time.Duration;
import java.util.Objects;

public record TimingMetricSnapshot(long samples, Duration average, Duration maximum) {
    public TimingMetricSnapshot {
        if (samples < 0) {
            throw new IllegalArgumentException("samples must not be negative");
        }
        Objects.requireNonNull(average, "average");
        Objects.requireNonNull(maximum, "maximum");
    }
}
