package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.market.statistics.model.ComparableSale;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class StatisticalMath {
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private StatisticalMath() {
    }

    public static BigDecimal mean(List<BigDecimal> values) {
        requireValues(values);
        return values.stream()
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT))
                .divide(BigDecimal.valueOf(values.size()), MATH_CONTEXT);
    }

    public static BigDecimal median(List<BigDecimal> values) {
        return percentile(values, new BigDecimal("0.5"));
    }

    /** Uses linear interpolation at index {@code (n - 1) * percentile}. */
    public static BigDecimal percentile(List<BigDecimal> values, BigDecimal percentile) {
        requireValues(values);
        Objects.requireNonNull(percentile, "percentile");
        if (percentile.signum() < 0 || percentile.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("percentile must be between zero and one");
        }
        List<BigDecimal> sorted = sorted(values);
        BigDecimal position = BigDecimal.valueOf(sorted.size() - 1)
                .multiply(percentile, MATH_CONTEXT);
        int lowerIndex = position.setScale(0, RoundingMode.FLOOR).intValueExact();
        int upperIndex = position.setScale(0, RoundingMode.CEILING).intValueExact();
        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }
        BigDecimal fraction = position.subtract(BigDecimal.valueOf(lowerIndex), MATH_CONTEXT);
        BigDecimal lower = sorted.get(lowerIndex);
        BigDecimal span = sorted.get(upperIndex).subtract(lower, MATH_CONTEXT);
        return lower.add(span.multiply(fraction, MATH_CONTEXT), MATH_CONTEXT);
    }

    public static BigDecimal medianAbsoluteDeviation(List<BigDecimal> values) {
        BigDecimal center = median(values);
        List<BigDecimal> deviations = values.stream()
                .map(value -> value.subtract(center, MATH_CONTEXT).abs())
                .toList();
        return median(deviations);
    }

    /** Population standard deviation; the supplied rows are the complete selected snapshot. */
    public static BigDecimal populationStandardDeviation(List<BigDecimal> values) {
        requireValues(values);
        BigDecimal average = mean(values);
        BigDecimal variance = values.stream()
                .map(value -> value.subtract(average, MATH_CONTEXT).pow(2, MATH_CONTEXT))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT))
                .divide(BigDecimal.valueOf(values.size()), MATH_CONTEXT);
        return variance.sqrt(MATH_CONTEXT);
    }

    public static BigDecimal weightedMedian(List<ComparableSale> sales) {
        if (sales == null || sales.isEmpty()) {
            throw new IllegalArgumentException("sales must not be empty");
        }
        List<ComparableSale> sorted = sales.stream()
                .sorted(Comparator.comparing(ComparableSale::comparablePrice))
                .toList();
        BigDecimal totalWeight = sorted.stream()
                .map(ComparableSale::recencyWeight)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT));
        BigDecimal midpoint = totalWeight.divide(TWO, MATH_CONTEXT);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int index = 0; index < sorted.size(); index++) {
            ComparableSale sale = sorted.get(index);
            cumulative = cumulative.add(sale.recencyWeight(), MATH_CONTEXT);
            int comparison = cumulative.compareTo(midpoint);
            if (comparison > 0) {
                return sale.comparablePrice();
            }
            if (comparison == 0 && index + 1 < sorted.size()) {
                return sale.comparablePrice()
                        .add(sorted.get(index + 1).comparablePrice(), MATH_CONTEXT)
                        .divide(TWO, MATH_CONTEXT);
            }
        }
        return sorted.getLast().comparablePrice();
    }

    private static List<BigDecimal> sorted(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    private static void requireValues(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("values must not contain null");
        }
    }
}
