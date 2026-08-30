package com.example.donutflipscanner.database;

import com.example.donutflipscanner.util.HashingUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class StableMarketKeyFactory {
    private StableMarketKeyFactory() {
    }

    public static String listingKey(
            Optional<String> remoteListingId,
            String sellerIdentifier,
            String itemFingerprint,
            BigDecimal listingPrice,
            int itemCount,
            Optional<Instant> listedAt,
            Optional<Instant> expiresAt
    ) {
        if (remoteListingId.isPresent() && !remoteListingId.get().isBlank()) {
            return "listing:" + HashingUtil.sha256Fields(List.of("remote", remoteListingId.get()));
        }
        return "listing:" + HashingUtil.sha256Fields(List.of(
                "derived",
                safe(sellerIdentifier),
                safe(itemFingerprint),
                DatabaseValues.decimal(listingPrice),
                Integer.toString(itemCount),
                listedAt.map(instant -> Long.toString(instant.toEpochMilli())).orElse(""),
                expiresAt.map(instant -> Long.toString(instant.toEpochMilli())).orElse("")
        ));
    }

    public static String saleKey(
            Optional<String> remoteTransactionId,
            String sellerIdentifier,
            String buyerIdentifier,
            String itemFingerprint,
            BigDecimal salePrice,
            int itemCount,
            Instant soldAt
    ) {
        if (remoteTransactionId.isPresent() && !remoteTransactionId.get().isBlank()) {
            return "sale:" + HashingUtil.sha256Fields(List.of("remote", remoteTransactionId.get()));
        }
        return "sale:" + HashingUtil.sha256Fields(List.of(
                "derived",
                safe(sellerIdentifier),
                safe(buyerIdentifier),
                safe(itemFingerprint),
                DatabaseValues.decimal(salePrice),
                Integer.toString(itemCount),
                Long.toString(soldAt.toEpochMilli())
        ));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
