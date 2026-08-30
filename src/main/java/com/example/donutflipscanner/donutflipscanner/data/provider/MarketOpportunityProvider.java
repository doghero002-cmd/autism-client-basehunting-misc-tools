package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.OpportunityActionResult;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.provider.MarketOpportunitySnapshot;
import com.example.donutflipscanner.provider.LiveMarketSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class MarketOpportunityProvider implements OpportunityProvider {
    private static final String MANUAL_REVIEW_MESSAGE =
            "Auction integration does not select or buy listings. Open the auction house manually and verify "
                    + "item, count, price, seller, enchantments, and metadata before purchasing.";

    private final LiveMarketSnapshotService snapshots;
    private final Clock clock;
    private volatile LiveMarketSnapshot cachedSource;
    private volatile List<FlipOpportunity> cachedOpportunities = List.of();

    public MarketOpportunityProvider(LiveMarketSnapshotService snapshots) {
        this(snapshots, Clock.systemUTC());
    }

    public MarketOpportunityProvider(LiveMarketSnapshotService snapshots, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<FlipOpportunity> getOpportunities() {
        LiveMarketSnapshot current = snapshots.snapshot();
        if (current != cachedSource) {
            synchronized (this) {
                if (current != cachedSource) {
                    cachedOpportunities = current.activeOpportunities().stream().map(this::map).toList();
                    cachedSource = current;
                }
            }
        }
        Instant verifiedAfter = clock.instant().minus(
                LiveMarketSnapshotService.MAXIMUM_ACTIONABLE_VERIFICATION_AGE
        );
        return cachedOpportunities.stream()
                .filter(value -> !value.lastVerifiedAt().isBefore(verifiedAfter))
                .toList();
    }

    @Override
    public CompletableFuture<OpportunityActionResult> reviewManually(String opportunityId) {
        return snapshots.updateState(opportunityId, OpportunityState.REVIEWED, "Opened for manual review")
                .thenApply(updated -> new OpportunityActionResult(updated, MANUAL_REVIEW_MESSAGE));
    }

    @Override
    public CompletableFuture<Boolean> dismiss(String opportunityId) {
        return snapshots.updateState(opportunityId, OpportunityState.DISMISSED, "Dismissed by user");
    }

    @Override
    public CompletableFuture<Boolean> markPurchasedManually(String opportunityId) {
        return snapshots.confirmManualPurchase(opportunityId);
    }

    private FlipOpportunity map(MarketOpportunitySnapshot value) {
        List<String> explanation = value.evaluationDetails()
                .map(List::of)
                .orElseGet(() -> List.of("Stored opportunity evaluation " + value.state() + "."));
        List<String> warnings = value.rejectionReason().map(List::of).orElseGet(List::of);
        return new FlipOpportunity(
                value.opportunityId(), value.listingKey(), value.itemFingerprint(),
                value.itemId(), ClientDataFormat.itemName(value.itemId()),
                value.itemCount(), ClientDataFormat.saturatedLong(value.listingPrice()),
                ClientDataFormat.saturatedLong(value.conservativeFairValue()),
                ClientDataFormat.saturatedLong(value.estimatedProfit()), value.roiPercent().doubleValue(),
                value.confidencePercent().doubleValue(), value.comparableSales(), value.riskLevel(),
                0L, 0L, "Stored evidence",
                "VERIFIED " + ClientDataFormat.age(value.lastVerifiedAt(), clock),
                value.sellerName().orElse("Hidden"), value.state(),
                value.listedAt().orElse(value.detectedAt()), value.lastVerifiedAt(), explanation, warnings,
                value.normalizedItemMetadata()
        );
    }
}
