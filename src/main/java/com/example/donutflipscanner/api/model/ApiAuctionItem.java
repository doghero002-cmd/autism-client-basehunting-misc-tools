package com.example.donutflipscanner.api.model;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public record ApiAuctionItem(
        Optional<String> id,
        OptionalInt count,
        Optional<String> displayName,
        List<String> lore,
        Optional<ApiItemData> itemData,
        List<ApiContainerItem> contents,
        List<String> unrecognizedFields
) {
    public ApiAuctionItem(
            Optional<String> id,
            OptionalInt count,
            Optional<String> displayName,
            List<String> lore,
            Optional<ApiItemData> itemData,
            List<ApiContainerItem> contents
    ) {
        this(id, count, displayName, lore, itemData, contents, List.of());
    }

    public ApiAuctionItem {
        id = id == null ? Optional.empty() : id;
        count = count == null ? OptionalInt.empty() : count;
        displayName = displayName == null ? Optional.empty() : displayName;
        lore = lore == null ? List.of() : List.copyOf(lore);
        itemData = itemData == null ? Optional.empty() : itemData;
        contents = contents == null ? List.of() : List.copyOf(contents);
        unrecognizedFields = unrecognizedFields == null ? List.of() : List.copyOf(unrecognizedFields);
    }
}
