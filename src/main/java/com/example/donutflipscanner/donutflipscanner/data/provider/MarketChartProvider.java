package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.MarketChartSnapshot;

@FunctionalInterface
public interface MarketChartProvider {
    MarketChartSnapshot getMarketChart();
}
