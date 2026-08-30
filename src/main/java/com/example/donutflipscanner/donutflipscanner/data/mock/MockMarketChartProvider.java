package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.MarketChartPoint;
import com.example.donutflipscanner.data.MarketChartSnapshot;
import com.example.donutflipscanner.data.provider.MarketChartProvider;

import java.util.List;

public final class MockMarketChartProvider implements MarketChartProvider {
    private static final MarketChartSnapshot SNAPSHOT = new MarketChartSnapshot(
            "MARKET INDEX",
            "MOCK 1H",
            28_700_000L,
            8.6D,
            List.of(
                    new MarketChartPoint("-60m", 22_400_000L),
                    new MarketChartPoint("-55m", 22_900_000L),
                    new MarketChartPoint("-50m", 22_600_000L),
                    new MarketChartPoint("-45m", 23_800_000L),
                    new MarketChartPoint("-40m", 24_100_000L),
                    new MarketChartPoint("-35m", 23_700_000L),
                    new MarketChartPoint("-30m", 25_200_000L),
                    new MarketChartPoint("-25m", 24_800_000L),
                    new MarketChartPoint("-20m", 26_100_000L),
                    new MarketChartPoint("-15m", 25_700_000L),
                    new MarketChartPoint("-10m", 27_300_000L),
                    new MarketChartPoint("-5m", 27_900_000L),
                    new MarketChartPoint("now", 28_700_000L)
            )
    );

    @Override
    public MarketChartSnapshot getMarketChart() {
        return SNAPSHOT;
    }
}
