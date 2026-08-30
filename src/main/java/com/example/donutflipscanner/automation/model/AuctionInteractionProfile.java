package com.example.donutflipscanner.automation.model;

import java.util.List;
import java.util.Objects;

/**
 * Explicit semantic contract for a private/authorized server auction UI.
 * There is deliberately no built-in profile for any public server.
 */
public record AuctionInteractionProfile(
        String profileId,
        String searchCommandTemplate,
        String resultsScreenTitle,
        int firstResultSlot,
        int lastResultSlot,
        String purchaseConfirmationTitle,
        int purchaseConfirmationSlot,
        String listingCommandTemplate,
        String listingCreatedTitle,
        String listingKeyLorePrefix,
        String priceLorePrefix,
        String sellerLorePrefix,
        int nextPageSlot,
        int previousPageSlot,
        int maximumPages,
        List<String> ignoredLorePrefixes
) {
    public AuctionInteractionProfile {
        profileId = bounded(profileId, "profileId", 64);
        searchCommandTemplate = command(searchCommandTemplate, "searchCommandTemplate");
        resultsScreenTitle = bounded(resultsScreenTitle, "resultsScreenTitle", 128);
        purchaseConfirmationTitle = bounded(purchaseConfirmationTitle, "purchaseConfirmationTitle", 128);
        listingCommandTemplate = command(listingCommandTemplate, "listingCommandTemplate");
        listingCreatedTitle = bounded(listingCreatedTitle, "listingCreatedTitle", 128);
        listingKeyLorePrefix = optionalBounded(listingKeyLorePrefix, "listingKeyLorePrefix", 64);
        priceLorePrefix = bounded(priceLorePrefix, "priceLorePrefix", 64);
        sellerLorePrefix = bounded(sellerLorePrefix, "sellerLorePrefix", 64);
        ignoredLorePrefixes = List.copyOf(Objects.requireNonNullElse(ignoredLorePrefixes, List.of()))
                .stream().map(value -> bounded(value, "ignoredLorePrefix", 64)).distinct().toList();
        if (!listingCommandTemplate.contains("{price}")) {
            throw new IllegalArgumentException("listing command must contain {price}");
        }
        if (firstResultSlot < 0 || lastResultSlot < firstResultSlot || lastResultSlot > 255
                || purchaseConfirmationSlot < 0 || purchaseConfirmationSlot > 255
                || nextPageSlot < -1 || nextPageSlot > 255
                || previousPageSlot < -1 || previousPageSlot > 255
                || maximumPages < 1 || maximumPages > 100
                || (maximumPages > 1 && (nextPageSlot < 0 || previousPageSlot < 0))) {
            throw new IllegalArgumentException("auction slot indices are invalid");
        }
    }

    public AuctionInteractionProfile(
            String profileId,
            String searchCommandTemplate,
            String resultsScreenTitle,
            int firstResultSlot,
            int lastResultSlot,
            String purchaseConfirmationTitle,
            int purchaseConfirmationSlot,
            String listingCommandTemplate,
            String listingCreatedTitle,
            String listingKeyLorePrefix,
            String priceLorePrefix,
            String sellerLorePrefix
    ) {
        this(profileId, searchCommandTemplate, resultsScreenTitle, firstResultSlot, lastResultSlot,
                purchaseConfirmationTitle, purchaseConfirmationSlot, listingCommandTemplate,
                listingCreatedTitle, listingKeyLorePrefix, priceLorePrefix, sellerLorePrefix,
                -1, -1, 1, List.of());
    }

    private static String command(String value, String name) {
        String result = bounded(value, name, 256);
        if (result.startsWith("/") || result.contains("\n") || result.contains("\r")) {
            throw new IllegalArgumentException(name + " must omit the leading slash and line breaks");
        }
        return result;
    }

    private static String bounded(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String result = value.strip();
        if (result.isEmpty() || result.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be nonblank and bounded");
        }
        return result;
    }

    private static String optionalBounded(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String result = value.strip();
        if (result.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return result;
    }
}
