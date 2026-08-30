package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.RelistPricingStrategy;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.configuration.AutomationConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class RelistPricingService {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public RelistPlan plan(TradeExecutionRequest request, AutomationConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        BigDecimal purchase = request.expectedListingPrice();
        BigDecimal fairValue = request.conservativeFairValue();
        BigDecimal proposed = switch (config.relistPricingStrategy()) {
            case CONSERVATIVE_FAIR_VALUE, UNDERCUT_LOWEST_CREDIBLE_ASK -> fairValue;
            case TARGET_ROI -> percentageMarkup(purchase, config.targetRoi());
            case FIXED_MARKUP -> percentageMarkup(purchase, config.fixedMarkupPercent());
        };
        BigDecimal minimumForProfit = purchase.add(config.minimumNetProfit());
        if (minimumForProfit.compareTo(fairValue) > 0) {
            throw new IllegalArgumentException("minimum required net profit exceeds conservative fair value");
        }
        BigDecimal bounded = proposed.max(minimumForProfit).min(fairValue)
                .setScale(0, RoundingMode.DOWN);
        if (bounded.signum() <= 0) {
            throw new IllegalArgumentException("relist plan produced a non-positive price");
        }
        RelistPricingStrategy strategy = config.relistPricingStrategy();
        String explanation = strategy == RelistPricingStrategy.UNDERCUT_LOWEST_CREDIBLE_ASK
                ? "No independently verified ask was supplied; conservative fair value was used."
                : "Price is bounded by conservative fair value and configured minimum net profit.";
        return new RelistPlan(bounded, strategy, explanation);
    }

    private static BigDecimal percentageMarkup(BigDecimal purchasePrice, BigDecimal percent) {
        return purchasePrice.multiply(
                BigDecimal.ONE.add(percent.divide(ONE_HUNDRED)),
                com.example.donutflipscanner.market.statistics.StatisticalMath.MATH_CONTEXT
        );
    }
}
