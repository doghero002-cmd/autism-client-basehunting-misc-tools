package com.example.donutflipscanner.market.confidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** GUI-neutral lines for the existing/future confidence tooltip adapter. */
public final class ConfidenceTooltipFormatter {
    private ConfidenceTooltipFormatter() {
    }

    public static List<String> lines(ConfidenceBreakdown breakdown) {
        Objects.requireNonNull(breakdown, "breakdown");
        List<String> lines = new ArrayList<>();
        lines.add("Confidence: " + breakdown.totalScore() + "%");
        for (ConfidenceCategoryDetail category : breakdown.categories()) {
            lines.add(label(category.category()) + ": " + category.score() + "/" + category.maximumScore());
        }
        for (AppliedConfidenceAdjustment adjustment : breakdown.adjustments()) {
            lines.add("Reduced " + adjustment.reductionPoints() + " points: " + adjustment.explanation());
        }
        for (AppliedConfidenceCap cap : breakdown.caps()) {
            lines.add("Cap " + cap.maximumScore() + "%: " + cap.explanation());
        }
        for (ConfidenceWarning warning : breakdown.warnings()) {
            lines.add("Warning: " + warning.message());
        }
        return List.copyOf(lines);
    }

    private static String label(ConfidenceCategory category) {
        return switch (category) {
            case COMPARABLE_SALES -> "Comparable sales";
            case SELLER_DIVERSITY -> "Seller diversity";
            case PRICE_STABILITY -> "Price stability";
            case ITEM_MATCH -> "Item match";
            case LIQUIDITY -> "Liquidity";
            case FRESHNESS -> "Freshness";
        };
    }
}
