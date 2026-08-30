package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ItemSearchResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Searches the live Minecraft item registry. This is the Mojang-mappings
 * replacement for the removed Yarn-backed provider.
 */
public final class RegistryItemSearchProvider implements ItemSearchProvider {
    private static final int MAX_RESULTS = 50;

    @Override
    public List<ItemSearchResult> search(String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<ItemSearchResult> results = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String idText = id.toString();
            String displayName = new ItemStack(item).getHoverName().getString();
            if (!needle.isEmpty()
                    && !idText.toLowerCase(Locale.ROOT).contains(needle)
                    && !displayName.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            results.add(new ItemSearchResult(idText, displayName));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        results.sort(Comparator.comparing(ItemSearchResult::itemId));
        return results;
    }
}
