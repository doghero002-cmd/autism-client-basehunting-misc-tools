package com.example.donutflipscanner.command;

import com.example.donutflipscanner.data.FlipOpportunity;

import java.util.Locale;
import java.util.Objects;

/** Builds clipboard-only DonutSMP Auction House commands from an immutable UI snapshot. */
public final class AuctionSearchCommand {
    private static final String PREFIX = "/ah ";
    private static final String SELL_PREFIX = "/ah sell ";
    private static final int MAXIMUM_SEARCH_TOKEN_LENGTH = 96;

    private AuctionSearchCommand() {
    }

    public static String forOpportunity(FlipOpportunity opportunity) {
        Objects.requireNonNull(opportunity, "opportunity");
        return forItem(opportunity.itemId(), opportunity.itemName());
    }

    public static String sellAtTargetPrice(FlipOpportunity opportunity) {
        Objects.requireNonNull(opportunity, "opportunity");
        return SELL_PREFIX + Math.max(1L, opportunity.fairValue());
    }

    static String forItem(String itemId, String itemName) {
        String identifierPath = Objects.requireNonNullElse(itemId, "").strip();
        int namespaceSeparator = identifierPath.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator + 1 < identifierPath.length()) {
            identifierPath = identifierPath.substring(namespaceSeparator + 1);
        }
        String searchToken = sanitize(identifierPath);
        if (searchToken.isBlank()) {
            searchToken = sanitize(Objects.requireNonNullElse(itemName, "item"));
        }
        if (searchToken.isBlank()) {
            searchToken = "item";
        }
        return PREFIX + searchToken;
    }

    private static String sanitize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sanitized = new StringBuilder(Math.min(lower.length(), MAXIMUM_SEARCH_TOKEN_LENGTH));
        boolean separatorPending = false;
        for (int index = 0; index < lower.length() && sanitized.length() < MAXIMUM_SEARCH_TOKEN_LENGTH; index++) {
            char character = lower.charAt(index);
            if (character >= 'a' && character <= 'z' || character >= '0' && character <= '9') {
                if (separatorPending && !sanitized.isEmpty()) {
                    sanitized.append('_');
                }
                sanitized.append(character);
                separatorPending = false;
            } else if (!sanitized.isEmpty()) {
                separatorPending = true;
            }
        }
        return sanitized.toString();
    }
}
