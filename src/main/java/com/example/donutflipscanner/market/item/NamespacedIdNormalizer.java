package com.example.donutflipscanner.market.item;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class NamespacedIdNormalizer {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    Result normalize(Optional<String> value) {
        if (value.isEmpty() || value.get().isBlank()) {
            return new Result("unknown:missing", false, true);
        }
        String normalized = value.get().trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        return new Result(normalized, VALID_ID.matcher(normalized).matches(), false);
    }

    record Result(String value, boolean valid, boolean missing) {
    }
}
