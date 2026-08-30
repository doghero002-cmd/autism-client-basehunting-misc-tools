package com.example.donutflipscanner.automation.model;

import java.util.List;
import java.util.Objects;

public record AuctionSlotSnapshot(
        int slotId,
        String itemId,
        int itemCount,
        String itemFingerprint,
        List<String> loreLines
) {
    public AuctionSlotSnapshot {
        if (slotId < 0 || itemCount < 1) {
            throw new IllegalArgumentException("auction slot values are invalid");
        }
        itemId = Objects.requireNonNull(itemId, "itemId");
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        if (itemFingerprint.isBlank()) {
            throw new IllegalArgumentException("itemFingerprint must not be blank");
        }
        loreLines = List.copyOf(Objects.requireNonNull(loreLines, "loreLines"));
    }
}
