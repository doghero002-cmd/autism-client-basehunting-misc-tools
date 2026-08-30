package com.example.donutflipscanner.api.model;

import java.util.List;

public record ApiAuctionPage(int status, List<ApiAuctionListing> listings, ApiPaginationMetadata pagination) {
    public ApiAuctionPage {
        listings = listings == null ? List.of() : List.copyOf(listings);
    }
}
