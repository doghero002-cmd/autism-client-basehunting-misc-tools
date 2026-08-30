package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ActiveAskStatistics;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ActiveAskCalculator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ActiveAskStatistics calculate(NormalizedItem item, List<ListingEntity> sourceListings) {
        Objects.requireNonNull(item, "item");
        List<ListingEntity> listings = List.copyOf(Objects.requireNonNull(sourceListings, "sourceListings"));
        if (item.matchQuality().matchType() == ItemMatchType.UNSUPPORTED) {
            return ActiveAskStatistics.empty();
        }
        String fingerprint = item.fingerprint().sha256();
        List<ListingEntity> active = listings.stream()
                .filter(listing -> listing.state() == ListingState.ACTIVE)
                .filter(listing -> listing.itemFingerprint().equals(fingerprint))
                .filter(listing -> askPrice(item.matchQuality().matchType(), listing).signum() > 0)
                .toList();
        if (active.isEmpty()) {
            return ActiveAskStatistics.empty();
        }

        List<BigDecimal> asks = active.stream()
                .map(listing -> askPrice(item.matchQuality().matchType(), listing))
                .sorted()
                .toList();
        long supply = active.stream().mapToLong(ListingEntity::itemCount).sum();
        Map<String, Integer> sellerCounts = new HashMap<>();
        for (ListingEntity listing : active) {
            String seller = listing.sellerUuid().filter(value -> !value.isBlank())
                    .or(() -> listing.sellerName().filter(value -> !value.isBlank()))
                    .orElse("unknown:" + listing.listingKey());
            sellerCounts.merge(seller, 1, Integer::sum);
        }
        int largestSellerCount = sellerCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        BigDecimal concentration = BigDecimal.valueOf(largestSellerCount)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT)
                .divide(BigDecimal.valueOf(active.size()), StatisticalMath.MATH_CONTEXT);

        return new ActiveAskStatistics(
                Optional.of(asks.getFirst()),
                asks.size() > 1 ? Optional.of(asks.get(1)) : Optional.empty(),
                Optional.of(StatisticalMath.median(asks)),
                active.size(),
                supply,
                sellerCounts.size(),
                Optional.of(concentration)
        );
    }

    private static BigDecimal askPrice(ItemMatchType matchType, ListingEntity listing) {
        if (matchType.unitPriceBased()) {
            return listing.unitPrice().orElseGet(() -> listing.listingPrice().divide(
                    BigDecimal.valueOf(listing.itemCount()), StatisticalMath.MATH_CONTEXT
            ));
        }
        return listing.listingPrice();
    }
}
