package com.example.donutflipscanner.database;

public record BatchWriteResult(int submitted, int inserted, int updatedOrIgnored) {
    public BatchWriteResult {
        if (submitted < 0 || inserted < 0 || updatedOrIgnored < 0 || inserted + updatedOrIgnored != submitted) {
            throw new IllegalArgumentException("Invalid batch-write counts");
        }
    }
}
