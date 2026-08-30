package com.example.donutflipscanner.api.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;

public record ApiCompletedTransaction(
        Optional<ApiAuctionItem> item,
        Optional<BigDecimal> price,
        Optional<ApiSeller> seller,
        Optional<BigInteger> unixMillisDateSold
) {
    public ApiCompletedTransaction {
        item = item == null ? Optional.empty() : item;
        price = price == null ? Optional.empty() : price;
        seller = seller == null ? Optional.empty() : seller;
        unixMillisDateSold = unixMillisDateSold == null ? Optional.empty() : unixMillisDateSold;
    }

    public Optional<Instant> soldAt() {
        if (unixMillisDateSold.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(unixMillisDateSold.get().longValueExact()));
        } catch (ArithmeticException | DateTimeException ignored) {
            return Optional.empty();
        }
    }
}
