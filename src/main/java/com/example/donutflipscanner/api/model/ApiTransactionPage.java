package com.example.donutflipscanner.api.model;

import java.util.List;

public record ApiTransactionPage(
        int status,
        List<ApiCompletedTransaction> transactions,
        ApiPaginationMetadata pagination
) {
    public ApiTransactionPage {
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }
}
