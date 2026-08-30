package com.example.donutflipscanner.api.model;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

public record ApiAuctionListing(
        Optional<ApiAuctionItem> item,
        Optional<BigDecimal> price,
        Optional<ApiSeller> seller,
        OptionalLong timeLeft
) {
    public ApiAuctionListing {
        item = item == null ? Optional.empty() : item;
        price = price == null ? Optional.empty() : price;
        seller = seller == null ? Optional.empty() : seller;
        timeLeft = timeLeft == null ? OptionalLong.empty() : timeLeft;
    }
}
