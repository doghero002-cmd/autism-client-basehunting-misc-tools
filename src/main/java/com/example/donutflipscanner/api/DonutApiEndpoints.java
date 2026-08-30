package com.example.donutflipscanner.api;

import java.net.URI;

/** Endpoint paths verified against https://api.donutsmp.net/doc.json. */
public final class DonutApiEndpoints {
    public static final URI BASE_URI = URI.create("https://api.donutsmp.net/");
    public static final String AUCTION_LISTINGS_PATH = "v1/auction/list/%d";
    public static final String AUCTION_TRANSACTIONS_PATH = "v1/auction/transactions/%d";
    public static final String PLAYER_STATS_PATH = "v1/stats/%s";
    public static final String RECENTLY_LISTED_SORT = "recently_listed";
    public static final int MINIMUM_PAGE = 1;
    public static final int MAXIMUM_TRANSACTION_PAGE = 10;
    public static final int DOCUMENTED_TRANSACTIONS_PER_PAGE = 100;

    private DonutApiEndpoints() {
    }

    public static URI auctionListings(int page) {
        requirePage(page);
        return BASE_URI.resolve(AUCTION_LISTINGS_PATH.formatted(page));
    }

    public static URI auctionTransactions(int page) {
        if (page < MINIMUM_PAGE || page > MAXIMUM_TRANSACTION_PAGE) {
            throw new IllegalArgumentException("Transaction page must be between 1 and 10");
        }
        return BASE_URI.resolve(AUCTION_TRANSACTIONS_PATH.formatted(page));
    }

    public static URI playerStats(String username) {
        String value = username == null ? "" : username.strip();
        if (!value.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("username must be a valid Minecraft profile name");
        }
        return BASE_URI.resolve(PLAYER_STATS_PATH.formatted(value));
    }

    private static void requirePage(int page) {
        if (page < MINIMUM_PAGE) {
            throw new IllegalArgumentException("Page must be at least one");
        }
    }
}
