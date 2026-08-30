package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AuctionListingCandidate;
import com.example.donutflipscanner.automation.model.AuctionLocateResult;
import com.example.donutflipscanner.automation.model.AuctionVerificationResult;
import com.example.donutflipscanner.automation.model.InventoryVerificationResult;
import com.example.donutflipscanner.automation.model.ListingResult;
import com.example.donutflipscanner.automation.model.ListingVerificationResult;
import com.example.donutflipscanner.automation.model.PurchaseResult;
import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;

import java.util.concurrent.CompletableFuture;

public interface AuctionInteractionAdapter {
    CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request);

    CompletableFuture<AuctionVerificationResult> verifyListing(
            TradeExecutionRequest request,
            AuctionListingCandidate candidate
    );

    CompletableFuture<PurchaseResult> purchase(
            TradeExecutionRequest request,
            AuctionListingCandidate candidate
    );

    CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request);

    CompletableFuture<ListingResult> listForSale(TradeExecutionRequest request, RelistPlan relistPlan);

    CompletableFuture<ListingVerificationResult> verifyListingCreated(
            TradeExecutionRequest request,
            RelistPlan relistPlan
    );

    CompletableFuture<Void> returnToSafeScreen();
}
