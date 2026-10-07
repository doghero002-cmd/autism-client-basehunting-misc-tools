package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RobustStatsTest {

    @Test
    void trollSalesAreSplitOutNotDeleted() {
        List<Double> prices = new ArrayList<>(List.of(95.0, 100.0, 102.0, 98.0, 105.0, 99.0, 101.0, 97.0, 103.0, 100.0,
            10_000_000.0, 1.0));
        RobustStats.OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(prices, d -> d);
        assertEquals(10, split.kept().size());
        assertEquals(2, split.outliers().size());
        assertTrue(split.outliers().contains(10_000_000.0));
        assertTrue(split.outliers().contains(1.0));
    }

    @Test
    void tooFewSamplesAreNeverOutliers() {
        RobustStats.OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(List.of(1.0, 1_000_000.0), d -> d);
        assertEquals(2, split.kept().size());
    }

    @Test
    void nonPositivePricesAlwaysExcluded() {
        RobustStats.OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(List.of(0.0, -5.0, 10.0, 11.0, 12.0), d -> d);
        assertEquals(3, split.kept().size());
        assertEquals(2, split.outliers().size());
    }

    @Test
    void recencyWeightHalvesEveryHalfLife() {
        assertEquals(1.0, RobustStats.recencyWeight(0, 1000), 1e-12);
        assertEquals(0.5, RobustStats.recencyWeight(1000, 1000), 1e-12);
        assertEquals(0.25, RobustStats.recencyWeight(2000, 1000), 1e-12);
    }

    @Test
    void weightedPercentileFavorsHeavierSamples() {
        // Unweighted median of {10,20,100} is 20; a heavy 100 must pull it well above that.
        List<RobustStats.WeightedValue> v = List.of(new RobustStats.WeightedValue(10, 1),
            new RobustStats.WeightedValue(20, 1), new RobustStats.WeightedValue(100, 10));
        assertEquals(52, RobustStats.weightedPercentile(v, 0.5), 1e-9);
        assertTrue(Double.isNaN(RobustStats.weightedPercentile(List.of(), 0.5)));
    }

    @Test
    void volatilityTracksScatter() {
        double tight = RobustStats.robustLogVolatility(List.of(100.0, 101.0, 99.0, 100.0, 100.5));
        double wide = RobustStats.robustLogVolatility(List.of(50.0, 150.0, 80.0, 120.0, 100.0));
        assertTrue(tight < wide);
        assertTrue(Double.isNaN(RobustStats.robustLogVolatility(List.of(1.0, 2.0))));
    }
}
