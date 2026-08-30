package com.example.donutflipscanner.market.profit;

import com.example.donutflipscanner.market.statistics.StatisticalMath;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ProfitCalculator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ProfitEvaluation evaluate(ProfitEvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        EffectiveProfitThresholds thresholds = EffectiveProfitThresholds.resolve(
                request.config().globalThresholds(), request.itemThresholds()
        );
        if (!request.fairValue().sufficientData() || request.fairValue().conservativeValue().isEmpty()) {
            ProfitRejection rejection = new ProfitRejection(
                    ProfitRejectionCode.FAIR_VALUE_UNAVAILABLE,
                    "A sufficient conservative fair value is required before profit can be calculated.",
                    Optional.empty(),
                    Optional.empty()
            );
            return new ProfitEvaluation(
                    request.itemFingerprint(), Optional.empty(), thresholds, false, List.of(rejection)
            );
        }

        TradingCostConfig costs = request.config().tradingCosts();
        BigDecimal fairValue = request.fairValue().conservativeValue().orElseThrow();
        BigDecimal purchasePrice = request.purchasePrice();
        BigDecimal purchaseFee = percentOf(purchasePrice, costs.purchaseFeePercent());
        BigDecimal saleFee = percentOf(fairValue, costs.saleFeePercent());
        BigDecimal effectiveSafetyPercent = costs.safetyBufferPercent().multiply(
                request.fairValue().recommendedSafetyBufferMultiplier()
        );
        BigDecimal safetyBuffer = percentOf(fairValue, effectiveSafetyPercent);
        BigDecimal grossProfit = fairValue.subtract(purchasePrice);
        BigDecimal totalAcquisitionCost = purchasePrice
                .add(purchaseFee)
                .add(costs.flatCost());
        BigDecimal netProfit = fairValue
                .subtract(purchasePrice)
                .subtract(purchaseFee)
                .subtract(saleFee)
                .subtract(costs.flatCost())
                .subtract(safetyBuffer);
        Optional<BigDecimal> roi = totalAcquisitionCost.signum() > 0
                ? Optional.of(netProfit.divide(totalAcquisitionCost, StatisticalMath.MATH_CONTEXT)
                        .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT))
                : Optional.empty();
        BigDecimal projectedExposure = request.currentOpenExposure().add(totalAcquisitionCost);
        ProfitBreakdown breakdown = new ProfitBreakdown(
                fairValue,
                purchasePrice,
                grossProfit,
                purchaseFee,
                saleFee,
                costs.flatCost(),
                costs.safetyBufferPercent(),
                effectiveSafetyPercent,
                safetyBuffer,
                totalAcquisitionCost,
                netProfit,
                roi,
                projectedExposure
        );

        List<ProfitRejection> rejections = new ArrayList<>();
        if (purchasePrice.signum() <= 0) {
            rejections.add(rejection(
                    ProfitRejectionCode.PURCHASE_PRICE_NOT_POSITIVE,
                    "Purchase price must be greater than zero.",
                    purchasePrice,
                    BigDecimal.ZERO
            ));
        }
        if (grossProfit.compareTo(thresholds.minimumGrossProfit()) < 0) {
            rejections.add(belowMinimum(
                    ProfitRejectionCode.GROSS_PROFIT_BELOW_MINIMUM,
                    "Gross profit",
                    grossProfit,
                    thresholds.minimumGrossProfit(),
                    false
            ));
        }
        if (netProfit.compareTo(thresholds.minimumNetProfit()) < 0) {
            rejections.add(belowMinimum(
                    ProfitRejectionCode.NET_PROFIT_BELOW_MINIMUM,
                    "Net profit",
                    netProfit,
                    thresholds.minimumNetProfit(),
                    false
            ));
        }
        if (roi.isEmpty()) {
            rejections.add(new ProfitRejection(
                    ProfitRejectionCode.ROI_UNAVAILABLE,
                    "ROI is unavailable because total acquisition cost is zero.",
                    Optional.empty(),
                    Optional.of(thresholds.minimumRoiPercent())
            ));
        } else if (roi.orElseThrow().compareTo(thresholds.minimumRoiPercent()) < 0) {
            rejections.add(belowMinimum(
                    ProfitRejectionCode.ROI_BELOW_MINIMUM,
                    "ROI",
                    roi.orElseThrow(),
                    thresholds.minimumRoiPercent(),
                    true
            ));
        }
        thresholds.globalMaximumPurchasePrice().ifPresent(maximum -> {
            if (purchasePrice.compareTo(maximum) > 0) {
                rejections.add(aboveMaximum(
                        ProfitRejectionCode.GLOBAL_MAXIMUM_PURCHASE_PRICE_EXCEEDED,
                        "Purchase price",
                        purchasePrice,
                        maximum,
                        "global maximum"
                ));
            }
        });
        thresholds.itemMaximumPurchasePrice().ifPresent(maximum -> {
            if (purchasePrice.compareTo(maximum) > 0) {
                rejections.add(aboveMaximum(
                        ProfitRejectionCode.ITEM_MAXIMUM_PURCHASE_PRICE_EXCEEDED,
                        "Purchase price",
                        purchasePrice,
                        maximum,
                        "item maximum"
                ));
            }
        });
        applyCapitalLimits(request, totalAcquisitionCost, projectedExposure, rejections);

        return new ProfitEvaluation(
                request.itemFingerprint(),
                Optional.of(breakdown),
                thresholds,
                rejections.isEmpty(),
                rejections
        );
    }

    private static void applyCapitalLimits(
            ProfitEvaluationRequest request,
            BigDecimal acquisitionCost,
            BigDecimal projectedExposure,
            List<ProfitRejection> rejections
    ) {
        CapitalLimits limits = request.config().capitalLimits();
        if (limits.assumedBankroll().isPresent() && limits.maximumBankrollPercentPerPurchase().isPresent()) {
            BigDecimal maximumCapital = percentOf(
                    limits.assumedBankroll().orElseThrow(),
                    limits.maximumBankrollPercentPerPurchase().orElseThrow()
            );
            if (acquisitionCost.compareTo(maximumCapital) > 0) {
                rejections.add(aboveMaximum(
                        ProfitRejectionCode.BANKROLL_PERCENTAGE_LIMIT_EXCEEDED,
                        "Total acquisition cost",
                        acquisitionCost,
                        maximumCapital,
                        "configured bankroll allocation"
                ));
            }
        }
        limits.maximumTotalOpenExposure().ifPresent(maximum -> {
            if (projectedExposure.compareTo(maximum) > 0) {
                rejections.add(aboveMaximum(
                        ProfitRejectionCode.OPEN_EXPOSURE_LIMIT_EXCEEDED,
                        "Projected open exposure",
                        projectedExposure,
                        maximum,
                        "maximum total open exposure"
                ));
            }
        });
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent, StatisticalMath.MATH_CONTEXT)
                .divide(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
    }

    private static ProfitRejection belowMinimum(
            ProfitRejectionCode code,
            String label,
            BigDecimal actual,
            BigDecimal required,
            boolean percentage
    ) {
        String suffix = percentage ? "%" : "";
        return rejection(
                code,
                label + " " + display(actual) + suffix + " is below required "
                        + display(required) + suffix + ".",
                actual,
                required
        );
    }

    private static ProfitRejection aboveMaximum(
            ProfitRejectionCode code,
            String label,
            BigDecimal actual,
            BigDecimal required,
            String maximumLabel
    ) {
        return rejection(
                code,
                label + " " + display(actual) + " exceeds " + maximumLabel + " "
                        + display(required) + ".",
                actual,
                required
        );
    }

    private static ProfitRejection rejection(
            ProfitRejectionCode code,
            String message,
            BigDecimal actual,
            BigDecimal required
    ) {
        return new ProfitRejection(code, message, Optional.of(actual), Optional.of(required));
    }

    private static String display(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
