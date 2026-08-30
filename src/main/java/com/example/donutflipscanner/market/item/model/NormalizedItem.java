package com.example.donutflipscanner.market.item.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record NormalizedItem(
        String itemId,
        OptionalInt stackCount,
        Optional<String> customName,
        List<String> lore,
        List<NormalizedEnchantment> enchantments,
        Optional<NormalizedArmorTrim> armorTrim,
        List<NormalizedContainedItem> contents,
        boolean contentsTruncated,
        List<String> unrecognizedFields,
        ItemMatchQuality matchQuality,
        ItemFingerprint fingerprint
) {
    public NormalizedItem {
        Objects.requireNonNull(itemId, "itemId");
        stackCount = stackCount == null ? OptionalInt.empty() : stackCount;
        customName = customName == null ? Optional.empty() : customName;
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        armorTrim = armorTrim == null ? Optional.empty() : armorTrim;
        contents = contents == null ? List.of() : List.copyOf(contents);
        unrecognizedFields = unrecognizedFields == null ? List.of() : List.copyOf(unrecognizedFields);
        Objects.requireNonNull(matchQuality, "matchQuality");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public boolean commodityPriceEligible() {
        return matchQuality.matchType() == ItemMatchType.COMMODITY
                && stackCount.isPresent()
                && stackCount.getAsInt() > 0;
    }

    public boolean unitPriceEligible() {
        return matchQuality.matchType().unitPriceBased()
                && stackCount.isPresent()
                && stackCount.getAsInt() > 0;
    }
}
