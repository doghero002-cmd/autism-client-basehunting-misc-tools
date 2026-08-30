package com.example.donutflipscanner.api.model;

import java.util.Optional;
import java.util.OptionalInt;

public record ApiContainerItem(
        Optional<String> id,
        OptionalInt count,
        Optional<String> displayName,
        Optional<ApiItemData> itemData,
        java.util.List<String> unrecognizedFields
) {
    public ApiContainerItem(
            Optional<String> id,
            OptionalInt count,
            Optional<String> displayName,
            Optional<ApiItemData> itemData
    ) {
        this(id, count, displayName, itemData, java.util.List.of());
    }

    public ApiContainerItem {
        id = id == null ? Optional.empty() : id;
        count = count == null ? OptionalInt.empty() : count;
        displayName = displayName == null ? Optional.empty() : displayName;
        itemData = itemData == null ? Optional.empty() : itemData;
        unrecognizedFields = unrecognizedFields == null ? java.util.List.of() : java.util.List.copyOf(unrecognizedFields);
    }
}
