package com.example.donutflipscanner.market.item;

import java.util.Locale;
import java.util.Set;

/** Shared recognition of item families that need explicit market handling. */
public final class MarketItemFamilyPolicy {

    private static final Set<String> ORES = Set.of(
            "coal_ore", "deepslate_coal_ore",
            "iron_ore", "deepslate_iron_ore",
            "copper_ore", "deepslate_copper_ore",
            "gold_ore", "deepslate_gold_ore", "nether_gold_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "diamond_ore", "deepslate_diamond_ore",
            "nether_quartz_ore", "ancient_debris"
    );

    private static final Set<String> TOOL_ITEMS = Set.of(
            "bow", "crossbow", "trident", "shield", "elytra", "fishing_rod",
            "flint_and_steel", "shears", "brush", "mace", "carrot_on_a_stick",
            "warped_fungus_on_a_stick"
    );

    private static final Set<String> TOOL_SUFFIXES = Set.of(
            "_sword", "_pickaxe", "_axe", "_shovel", "_hoe"
    );

    private MarketItemFamilyPolicy() {
    }

    public static boolean isTool(String itemId) {
        String path = path(itemId);
        return TOOL_ITEMS.contains(path) || TOOL_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    public static Set<String> oreItemIds() {
        return ORES.stream().map(value -> "minecraft:" + value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String path(String itemId) {
        String normalized = itemId == null ? "" : itemId.strip().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }
}
