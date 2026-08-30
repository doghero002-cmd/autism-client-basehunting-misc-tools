package com.example.donutflipscanner.api.model;

import java.util.List;
import java.util.Optional;

public record ApiItemData(
        List<ApiEnchantment> enchantments,
        Optional<ApiArmorTrim> trim,
        List<String> unrecognizedFields
) {
    public ApiItemData(List<ApiEnchantment> enchantments, Optional<ApiArmorTrim> trim) {
        this(enchantments, trim, List.of());
    }

    public ApiItemData {
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        trim = trim == null ? Optional.empty() : trim;
        unrecognizedFields = unrecognizedFields == null ? List.of() : List.copyOf(unrecognizedFields);
    }
}
