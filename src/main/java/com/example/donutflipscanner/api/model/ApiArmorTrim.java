package com.example.donutflipscanner.api.model;

import java.util.List;
import java.util.Optional;

public record ApiArmorTrim(Optional<String> material, Optional<String> pattern, List<String> unrecognizedFields) {
    public ApiArmorTrim(Optional<String> material, Optional<String> pattern) {
        this(material, pattern, List.of());
    }

    public ApiArmorTrim {
        material = material == null ? Optional.empty() : material;
        pattern = pattern == null ? Optional.empty() : pattern;
        unrecognizedFields = unrecognizedFields == null ? List.of() : List.copyOf(unrecognizedFields);
    }
}
