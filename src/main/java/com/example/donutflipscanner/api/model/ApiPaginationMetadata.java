package com.example.donutflipscanner.api.model;

import java.util.OptionalInt;

/**
 * Pagination facts known by the client. The official auction schemas currently
 * return no total-page or total-item fields, so those values remain empty.
 */
public record ApiPaginationMetadata(
        int requestedPage,
        int returnedItems,
        OptionalInt documentedPageSize,
        OptionalInt documentedMaximumPage
) {
    public ApiPaginationMetadata {
        if (requestedPage < 1 || returnedItems < 0) {
            throw new IllegalArgumentException("Invalid pagination values");
        }
        documentedPageSize = documentedPageSize == null ? OptionalInt.empty() : documentedPageSize;
        documentedMaximumPage = documentedMaximumPage == null ? OptionalInt.empty() : documentedMaximumPage;
    }

    public static ApiPaginationMetadata listings(int requestedPage, int returnedItems) {
        return new ApiPaginationMetadata(requestedPage, returnedItems, OptionalInt.empty(), OptionalInt.empty());
    }

    public static ApiPaginationMetadata transactions(int requestedPage, int returnedItems) {
        return new ApiPaginationMetadata(requestedPage, returnedItems, OptionalInt.of(100), OptionalInt.of(10));
    }
}
