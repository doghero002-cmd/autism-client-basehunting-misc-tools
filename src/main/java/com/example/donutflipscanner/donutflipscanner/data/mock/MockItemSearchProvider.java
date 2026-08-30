package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.ItemSearchResult;
import com.example.donutflipscanner.data.provider.ItemSearchProvider;

import java.util.List;
import java.util.Locale;

public final class MockItemSearchProvider implements ItemSearchProvider {
    private static final List<ItemSearchResult> ITEMS = List.of(
            new ItemSearchResult("minecraft:netherite_ingot", "Netherite Ingot"),
            new ItemSearchResult("minecraft:totem_of_undying", "Totem of Undying"),
            new ItemSearchResult("minecraft:enchanted_golden_apple", "Enchanted Golden Apple"),
            new ItemSearchResult("minecraft:diamond_block", "Block of Diamond")
    );

    @Override
    public List<ItemSearchResult> search(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return ITEMS;
        }
        return ITEMS.stream()
                .filter(item -> item.itemId().contains(normalizedQuery)
                        || item.displayName().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .toList();
    }
}

