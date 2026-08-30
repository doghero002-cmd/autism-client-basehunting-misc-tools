package com.example.donutflipscanner.market.item.model;

import java.util.Optional;

public record ArmorTrimDescriptor(
        Optional<String> material,
        Optional<String> pattern,
        java.util.List<String> unrecognizedFields
) {
    public ArmorTrimDescriptor {
        material = material == null ? Optional.empty() : material;
        pattern = pattern == null ? Optional.empty() : pattern;
        unrecognizedFields = unrecognizedFields == null ? java.util.List.of() : java.util.List.copyOf(unrecognizedFields);
    }
}
