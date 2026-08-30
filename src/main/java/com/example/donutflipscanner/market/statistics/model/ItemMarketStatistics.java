package com.example.donutflipscanner.market.statistics.model;

import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ItemMarketStatistics(
        String itemFingerprint,
        MarketLookbackPeriod lookback,
        int sourceSaleCount,
        int comparableSaleCount,
        int excludedSaleCount,
        int uniqueSellerCount,
        int uniqueBuyerCount,
        Optional<BigDecimal> minimum,
        Optional<BigDecimal> maximum,
        Optional<BigDecimal> mean,
        Optional<BigDecimal> median,
        Optional<BigDecimal> weightedMedian,
        Optional<BigDecimal> percentile25,
        Optional<BigDecimal> percentile40,
        Optional<BigDecimal> percentile75,
        Optional<BigDecimal> interquartileRange,
        Optional<BigDecimal> medianAbsoluteDeviation,
        Optional<BigDecimal> standardDeviation,
        Optional<BigDecimal> coefficientOfVariation,
        Optional<BigDecimal> salesPerHour,
        Optional<BigDecimal> salesPerDay,
        Optional<Instant> mostRecentSaleAt,
        Optional<Duration> timeSinceMostRecentSale,
        boolean lowData,
        boolean stale,
        MarketDataStatus status,
        String dataQualityExplanation,
        ComparableSaleSet comparableSales,
        ActiveAskStatistics activeAsks,
        Instant calculatedAt
) {
    public ItemMarketStatistics {
        Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        Objects.requireNonNull(lookback, "lookback");
        if (sourceSaleCount < 0 || comparableSaleCount < 0 || excludedSaleCount < 0
                || uniqueSellerCount < 0 || uniqueBuyerCount < 0) {
            throw new IllegalArgumentException("statistics counts must not be negative");
        }
        minimum = optional(minimum);
        maximum = optional(maximum);
        mean = optional(mean);
        median = optional(median);
        weightedMedian = optional(weightedMedian);
        percentile25 = optional(percentile25);
        percentile40 = optional(percentile40);
        percentile75 = optional(percentile75);
        interquartileRange = optional(interquartileRange);
        medianAbsoluteDeviation = optional(medianAbsoluteDeviation);
        standardDeviation = optional(standardDeviation);
        coefficientOfVariation = optional(coefficientOfVariation);
        salesPerHour = optional(salesPerHour);
        salesPerDay = optional(salesPerDay);
        mostRecentSaleAt = Objects.requireNonNullElse(mostRecentSaleAt, Optional.empty());
        timeSinceMostRecentSale = Objects.requireNonNullElse(timeSinceMostRecentSale, Optional.empty());
        Objects.requireNonNull(status, "status");
        dataQualityExplanation = Objects.requireNonNull(dataQualityExplanation, "dataQualityExplanation");
        Objects.requireNonNull(comparableSales, "comparableSales");
        Objects.requireNonNull(activeAsks, "activeAsks");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
    }

    private static Optional<BigDecimal> optional(Optional<BigDecimal> value) {
        return Objects.requireNonNullElse(value, Optional.empty());
    }
}
