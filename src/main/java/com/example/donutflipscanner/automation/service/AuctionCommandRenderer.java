package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strictly renders configured chat commands without permitting command-value injection. */
public final class AuctionCommandRenderer {
    private static final int MAXIMUM_COMMAND_LENGTH = 256;
    private static final Pattern SAFE_COMMAND_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SAFE_COMMAND_NAME = Pattern.compile("[A-Za-z0-9 _.'-]{1,128}");

    public String search(AuctionInteractionProfile profile, TradeExecutionRequest request) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(request, "request");
        String command = profile.searchCommandTemplate()
                .replace("{listing_key}", safeValue(request.listingKey(), "listing key"))
                .replace("{item_id}", safeValue(request.itemId(), "item ID"))
                .replace("{price}", wholePositivePrice(request.expectedListingPrice()));
        if (command.contains("{seller}")) {
            String seller = request.expectedSeller().orElseThrow(() -> new IllegalArgumentException(
                    "The search command requires a seller, but the opportunity does not expose one."
            ));
            command = command.replace("{seller}", safeValue(seller, "seller"));
        }
        if (command.contains("{item_name}")) {
            String name = request.expectedItemName().orElseThrow(() -> new IllegalArgumentException(
                    "The search command requires an item name, but none is available."
            ));
            command = command.replace("{item_name}", safeName(name, "item name"));
        }
        return validateRendered(command, "Search");
    }

    public String listing(AuctionInteractionProfile profile, BigDecimal price) {
        Objects.requireNonNull(profile, "profile");
        return validateRendered(
                profile.listingCommandTemplate().replace("{price}", wholePositivePrice(price)),
                "Listing"
        );
    }

    private static String safeValue(String value, String name) {
        if (!SAFE_COMMAND_VALUE.matcher(Objects.requireNonNullElse(value, "")).matches()) {
            throw new IllegalArgumentException(name + " is not safe for a server command template");
        }
        return value;
    }

    private static String safeName(String value, String name) {
        if (!SAFE_COMMAND_NAME.matcher(Objects.requireNonNullElse(value, "")).matches()) {
            throw new IllegalArgumentException(name + " is not safe for a server command template");
        }
        return value;
    }

    private static String validateRendered(String command, String kind) {
        if (command.indexOf('{') >= 0 || command.indexOf('}') >= 0
                || command.length() > MAXIMUM_COMMAND_LENGTH || command.isBlank()
                || command.startsWith("/") || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    kind + " command contains an unknown placeholder or is invalid."
            );
        }
        return command;
    }

    private static String wholePositivePrice(BigDecimal value) {
        Objects.requireNonNull(value, "price");
        try {
            String result = value.toBigIntegerExact().toString();
            if (value.signum() <= 0) {
                throw new ArithmeticException("not positive");
            }
            return result;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Price must be a positive whole currency value.", failure
            );
        }
    }
}
