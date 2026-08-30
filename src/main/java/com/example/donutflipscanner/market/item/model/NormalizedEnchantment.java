package com.example.donutflipscanner.market.item.model;

import java.util.Objects;

public record NormalizedEnchantment(String id, int level) implements Comparable<NormalizedEnchantment> {
    public NormalizedEnchantment {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public int compareTo(NormalizedEnchantment other) {
        int idComparison = id.compareTo(other.id);
        return idComparison != 0 ? idComparison : Integer.compare(level, other.level);
    }
}
