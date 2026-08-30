package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingState;

import java.math.BigDecimal;
import java.util.Objects;

public record OpportunityRevision(
        BigDecimal listingPrice,
        ListingState listingState,
        long completedSalesVersion,
        String configurationVersion,
        String filterVersion
) {
    public OpportunityRevision {
        Objects.requireNonNull(listingPrice, "listingPrice");
        Objects.requireNonNull(listingState, "listingState");
        if (completedSalesVersion < 0) {
            throw new IllegalArgumentException("completedSalesVersion must not be negative");
        }
        Objects.requireNonNull(configurationVersion, "configurationVersion");
        Objects.requireNonNull(filterVersion, "filterVersion");
    }

    public static OpportunityRevision from(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config
    ) {
        return new OpportunityRevision(
                request.listing().listingPrice(), request.listing().state(), request.completedSalesVersion(),
                config.configurationVersion(), config.filterVersion()
        );
    }
}
