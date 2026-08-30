package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.AuctionSlotSnapshot;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact semantic matcher. Zero matches and ambiguous matches both fail closed. */
public final class StrictAuctionListingMatcher {
    public Match match(
            TradeExecutionRequest request,
            AuctionInteractionProfile profile,
            List<AuctionSlotSnapshot> slots
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(profile, "profile");
        List<AuctionSlotSnapshot> matches = List.copyOf(Objects.requireNonNull(slots, "slots")).stream()
                .filter(slot -> slot.slotId() >= profile.firstResultSlot()
                        && slot.slotId() <= profile.lastResultSlot())
                .filter(slot -> matchesSlot(request, profile, slot))
                .toList();
        if (matches.size() == 1) {
            return new Match(Optional.of(matches.getFirst()), "Exactly one immutable listing matched.");
        }
        return new Match(Optional.empty(), matches.isEmpty()
                ? "No visible listing matched fingerprint, item, count, listing ID (when exposed), price, and seller."
                : "Multiple visible listings matched; execution is ambiguous.");
    }

    /** Searches already captured pages and rejects duplicate matches across page boundaries. */
    public PageMatch matchPages(
            TradeExecutionRequest request,
            AuctionInteractionProfile profile,
            List<List<AuctionSlotSnapshot>> pages
    ) {
        Objects.requireNonNull(pages, "pages");
        List<PageSlot> matches = new java.util.ArrayList<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            for (AuctionSlotSnapshot slot : List.copyOf(pages.get(pageIndex))) {
                if (slot.slotId() >= profile.firstResultSlot()
                        && slot.slotId() <= profile.lastResultSlot()
                        && matchesSlot(request, profile, slot)) {
                    matches.add(new PageSlot(pageIndex, slot));
                }
            }
        }
        if (matches.size() == 1) {
            PageSlot found = matches.getFirst();
            return new PageMatch(Optional.of(found), "Exactly one immutable listing matched across all pages.");
        }
        return new PageMatch(Optional.empty(), matches.isEmpty()
                ? "No auction page contained the exact immutable listing."
                : "Multiple auction pages contained matching listings; execution is ambiguous.");
    }

    public boolean matchesSlot(
            TradeExecutionRequest request,
            AuctionInteractionProfile profile,
            AuctionSlotSnapshot slot
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(slot, "slot");
        return request.itemId().equals(slot.itemId())
                && request.expectedItemCount() == slot.itemCount()
                && request.itemFingerprint().equals(slot.itemFingerprint())
                && (profile.listingKeyLorePrefix().isBlank()
                || exactValue(slot.loreLines(), profile.listingKeyLorePrefix())
                .filter(request.listingKey()::equals).isPresent())
                && exactPrice(slot.loreLines(), profile.priceLorePrefix())
                .filter(value -> value.compareTo(request.expectedListingPrice()) == 0).isPresent()
                && (request.expectedSeller().isEmpty()
                || exactValue(slot.loreLines(), profile.sellerLorePrefix())
                .filter(request.expectedSeller().orElseThrow()::equals).isPresent());
    }

    private static Optional<String> exactValue(List<String> lines, String prefix) {
        return lines.stream().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).strip())
                .filter(value -> !value.isEmpty()).reduce((first, second) -> "");
    }

    private static Optional<BigDecimal> exactPrice(List<String> lines, String prefix) {
        Optional<String> value = exactValue(lines, prefix);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.orElseThrow().replace(",", "").replace("$", "").strip();
        try {
            return Optional.of(new BigDecimal(normalized));
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
    }

    public record Match(Optional<AuctionSlotSnapshot> slot, String message) {
        public Match {
            slot = Objects.requireNonNullElse(slot, Optional.empty());
            message = Objects.requireNonNullElse(message, "");
        }
    }

    public record PageSlot(int pageIndex, AuctionSlotSnapshot slot) {
        public PageSlot {
            if (pageIndex < 0) {
                throw new IllegalArgumentException("pageIndex must not be negative");
            }
            Objects.requireNonNull(slot, "slot");
        }
    }

    public record PageMatch(Optional<PageSlot> match, String message) {
        public PageMatch {
            match = Objects.requireNonNullElse(match, Optional.empty());
            message = Objects.requireNonNullElse(message, "");
        }
    }
}
