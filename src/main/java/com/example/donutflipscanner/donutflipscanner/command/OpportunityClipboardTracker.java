package com.example.donutflipscanner.command;

import com.example.donutflipscanner.data.FlipOpportunity;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Copies the highest-ranked newly seen listing once and suppresses repeat clipboard writes. */
public final class OpportunityClipboardTracker {
    private static final int MAXIMUM_TRACKED_LISTINGS = 512;

    private final Consumer<String> clipboardWriter;
    private final Set<String> seenListingKeys = new LinkedHashSet<>();

    public OpportunityClipboardTracker(Consumer<String> clipboardWriter) {
        this.clipboardWriter = Objects.requireNonNull(clipboardWriter, "clipboardWriter");
    }

    public synchronized Optional<String> copyFirstNew(List<FlipOpportunity> opportunities) {
        Objects.requireNonNull(opportunities, "opportunities");
        String command = null;
        for (FlipOpportunity opportunity : opportunities) {
            Objects.requireNonNull(opportunity, "opportunity");
            if (seenListingKeys.add(opportunity.listingKey()) && command == null) {
                command = AuctionSearchCommand.forOpportunity(opportunity);
            }
        }
        trimSeenListings();
        if (command == null) {
            return Optional.empty();
        }
        clipboardWriter.accept(command);
        return Optional.of(command);
    }

    private void trimSeenListings() {
        Iterator<String> iterator = seenListingKeys.iterator();
        while (seenListingKeys.size() > MAXIMUM_TRACKED_LISTINGS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
