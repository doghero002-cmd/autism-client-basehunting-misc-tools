package com.example.donutflipscanner.market.item.model;

import java.util.List;
import java.util.Objects;

public record ItemMatchQuality(
        ItemMatchType matchType,
        int score,
        List<ItemNormalizationIssue> issues
) {
    public ItemMatchQuality {
        Objects.requireNonNull(matchType, "matchType");
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between zero and one hundred");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ItemMatchQuality of(ItemMatchType matchType, List<ItemNormalizationIssue> issues) {
        return new ItemMatchQuality(matchType, matchType.defaultQualityScore(), issues);
    }
}
