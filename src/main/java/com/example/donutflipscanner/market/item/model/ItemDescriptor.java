package com.example.donutflipscanner.market.item.model;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Provider-neutral item metadata accepted by the normalizer. */
public record ItemDescriptor(
        Optional<String> itemId,
        OptionalInt stackCount,
        Optional<String> displayName,
        List<String> lore,
        List<ItemEnchantmentDescriptor> enchantments,
        Optional<ArmorTrimDescriptor> armorTrim,
        List<ItemDescriptor> contents,
        List<String> unrecognizedFields
) {
    public ItemDescriptor {
        itemId = itemId == null ? Optional.empty() : itemId;
        stackCount = stackCount == null ? OptionalInt.empty() : stackCount;
        displayName = displayName == null ? Optional.empty() : displayName;
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        armorTrim = armorTrim == null ? Optional.empty() : armorTrim;
        contents = contents == null ? List.of() : List.copyOf(contents);
        unrecognizedFields = unrecognizedFields == null ? List.of() : List.copyOf(unrecognizedFields);
    }

    public static ItemDescriptor simple(String itemId, int count) {
        return new ItemDescriptor(
                Optional.of(itemId),
                OptionalInt.of(count),
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of()
        );
    }
}
