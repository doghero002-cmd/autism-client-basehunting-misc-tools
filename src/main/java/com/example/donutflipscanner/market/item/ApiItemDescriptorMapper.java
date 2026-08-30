package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.api.model.ApiArmorTrim;
import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiContainerItem;
import com.example.donutflipscanner.api.model.ApiItemData;
import com.example.donutflipscanner.market.item.model.ArmorTrimDescriptor;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemEnchantmentDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Maps documented raw API item fields into the provider-neutral item descriptor. */
public final class ApiItemDescriptorMapper {
    public ItemDescriptor map(ApiAuctionItem item) {
        Objects.requireNonNull(item, "item");
        ItemDataFields itemData = item.itemData().map(this::itemData).orElseGet(ItemDataFields::empty);
        List<ItemDescriptor> contents = item.contents().stream().map(this::mapContainer).toList();
        List<String> unknown = new ArrayList<>(item.unrecognizedFields());
        itemData.unrecognizedFields().stream().map(field -> "enchants." + field).forEach(unknown::add);
        return new ItemDescriptor(
                item.id(),
                item.count(),
                item.displayName(),
                item.lore(),
                itemData.enchantments(),
                itemData.trim(),
                contents,
                unknown.stream().distinct().sorted().toList()
        );
    }

    private ItemDescriptor mapContainer(ApiContainerItem item) {
        ItemDataFields itemData = item.itemData().map(this::itemData).orElseGet(ItemDataFields::empty);
        List<String> unknown = new ArrayList<>(item.unrecognizedFields());
        itemData.unrecognizedFields().stream().map(field -> "enchants." + field).forEach(unknown::add);
        return new ItemDescriptor(
                item.id(),
                item.count(),
                item.displayName(),
                List.of(),
                itemData.enchantments(),
                itemData.trim(),
                List.of(),
                unknown.stream().distinct().sorted().toList()
        );
    }

    private ItemDataFields itemData(ApiItemData itemData) {
        List<ItemEnchantmentDescriptor> enchantments = itemData.enchantments().stream()
                .map(value -> new ItemEnchantmentDescriptor(value.id(), value.level()))
                .toList();
        List<String> unknown = new ArrayList<>(itemData.unrecognizedFields());
        Optional<ArmorTrimDescriptor> trim = itemData.trim().map(value -> {
            value.unrecognizedFields().stream().map(field -> "trim." + field).forEach(unknown::add);
            return trim(value);
        });
        return new ItemDataFields(enchantments, trim, unknown.stream().distinct().sorted().toList());
    }

    private ArmorTrimDescriptor trim(ApiArmorTrim trim) {
        return new ArmorTrimDescriptor(trim.material(), trim.pattern(), trim.unrecognizedFields());
    }

    private record ItemDataFields(
            List<ItemEnchantmentDescriptor> enchantments,
            Optional<ArmorTrimDescriptor> trim,
            List<String> unrecognizedFields
    ) {
        private static ItemDataFields empty() {
            return new ItemDataFields(List.of(), Optional.empty(), List.of());
        }
    }
}
