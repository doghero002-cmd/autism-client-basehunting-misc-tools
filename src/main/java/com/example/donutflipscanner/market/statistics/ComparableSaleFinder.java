package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleSet;
import com.example.donutflipscanner.market.statistics.model.RejectedComparableSale;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ComparableSaleFinder {
    private final OutlierFilter outlierFilter;

    public ComparableSaleFinder() {
        this(new OutlierFilter());
    }

    public ComparableSaleFinder(OutlierFilter outlierFilter) {
        this.outlierFilter = Objects.requireNonNull(outlierFilter, "outlierFilter");
    }

    public ComparableSaleSet find(
            NormalizedItem item,
            List<SaleEntity> sourceSales,
            MarketStatisticsConfig config,
            Instant now
    ) {
        Objects.requireNonNull(item, "item");
        List<SaleEntity> sales = List.copyOf(Objects.requireNonNull(sourceSales, "sourceSales"));
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");
        Optional<Instant> cutoff = config.lookback().cutoff(now);
        String fingerprint = item.fingerprint().sha256();
        List<ComparableSale> candidates = new ArrayList<>();
        List<RejectedComparableSale> rejected = new ArrayList<>();

        for (SaleEntity sale : sales) {
            ComparableSaleRejectionReason reason = validationFailure(item, sale, fingerprint, cutoff, now);
            if (reason != null) {
                rejected.add(reject(sale, reason));
                continue;
            }
            BigDecimal price = comparablePrice(item.matchQuality().matchType(), sale);
            if (price.signum() <= 0) {
                rejected.add(new RejectedComparableSale(
                        sale.saleKey(), ComparableSaleRejectionReason.INVALID_PRICE,
                        Optional.of(price), "Comparable price must be positive"
                ));
                continue;
            }
            candidates.add(new ComparableSale(
                    sale.saleKey(),
                    price,
                    sale.itemCount(),
                    identity(sale.sellerUuid(), sale.sellerName()),
                    identity(sale.buyerUuid(), sale.buyerName()),
                    sale.soldAt(),
                    config.recencyWeights().weight(sale.soldAt(), now)
            ));
        }

        OutlierFilter.Result filtered = outlierFilter.filter(candidates, config);
        rejected.addAll(filtered.rejected());
        return new ComparableSaleSet(sales.size(), filtered.accepted(), rejected, cutoff, now);
    }

    private static ComparableSaleRejectionReason validationFailure(
            NormalizedItem item,
            SaleEntity sale,
            String expectedFingerprint,
            Optional<Instant> cutoff,
            Instant now
    ) {
        if (item.matchQuality().matchType() == ItemMatchType.UNSUPPORTED) {
            return ComparableSaleRejectionReason.UNSUPPORTED_ITEM;
        }
        if (!expectedFingerprint.equals(sale.itemFingerprint())) {
            return ComparableSaleRejectionReason.FINGERPRINT_MISMATCH;
        }
        if (sale.itemCount() < 1) {
            return ComparableSaleRejectionReason.INVALID_ITEM_COUNT;
        }
        if (sale.soldAt().isAfter(now)) {
            return ComparableSaleRejectionReason.FUTURE_TIMESTAMP;
        }
        if (cutoff.isPresent() && sale.soldAt().isBefore(cutoff.get())) {
            return ComparableSaleRejectionReason.OUTSIDE_LOOKBACK;
        }
        return null;
    }

    private static BigDecimal comparablePrice(ItemMatchType matchType, SaleEntity sale) {
        if (matchType.unitPriceBased()) {
            return sale.unitPrice().orElseGet(() -> sale.salePrice().divide(
                    BigDecimal.valueOf(sale.itemCount()), StatisticalMath.MATH_CONTEXT
            ));
        }
        return sale.salePrice();
    }

    private static Optional<String> identity(Optional<String> uuid, Optional<String> name) {
        return uuid.filter(value -> !value.isBlank()).or(() -> name.filter(value -> !value.isBlank()));
    }

    private static RejectedComparableSale reject(SaleEntity sale, ComparableSaleRejectionReason reason) {
        String explanation = switch (reason) {
            case UNSUPPORTED_ITEM -> "Unsupported item fingerprints cannot produce comparable sales";
            case FINGERPRINT_MISMATCH -> "Sale fingerprint does not match the requested item";
            case OUTSIDE_LOOKBACK -> "Sale occurred before the configured lookback window";
            case FUTURE_TIMESTAMP -> "Sale timestamp is later than the snapshot time";
            case INVALID_PRICE -> "Comparable price must be positive";
            case INVALID_ITEM_COUNT -> "Item count must be positive";
            case MAD_OUTLIER, IQR_OUTLIER -> throw new IllegalArgumentException("Outliers are rejected later");
        };
        return new RejectedComparableSale(sale.saleKey(), reason, Optional.empty(), explanation);
    }
}
