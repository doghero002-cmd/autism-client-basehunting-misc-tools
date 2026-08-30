package com.example.donutflipscanner.market.item;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable, replaceable allow/caution registry used by item normalization. */
public final class SafeItemCategoryRegistry {
    public enum Category {
        COMMODITY,
        EXACT_SAFE,
        VISIBLE_METADATA_ONLY,
        CONTAINER,
        UNSUPPORTED,
        UNREGISTERED
    }

    private static final Set<String> DAMAGE_SENSITIVE_SUFFIXES = Set.of(
            "_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
            "_helmet", "_chestplate", "_leggings", "_boots"
    );

    private static final Set<String> DAMAGE_SENSITIVE_ITEMS = Set.of(
            "bow", "crossbow", "trident", "shield", "elytra", "fishing_rod",
            "flint_and_steel", "shears", "brush", "mace", "carrot_on_a_stick",
            "warped_fungus_on_a_stick"
    );

    private static final Set<String> VISIBLE_METADATA_VALUE_ITEMS = Set.of(
            "written_book", "enchanted_book", "map", "filled_map",
            "potion", "splash_potion", "lingering_potion", "tipped_arrow"
    );

    private static final Set<String> VALUE_METADATA_FAMILIES = Set.of(
            "written_book", "writable_book", "knowledge_book", "enchanted_book",
            "map", "filled_map", "player_head", "potion", "splash_potion",
            "lingering_potion", "tipped_arrow", "firework_rocket", "firework_star",
            "suspicious_stew", "goat_horn", "compass", "recovery_compass",
            "decorated_pot", "bundle"
    );

    private final Set<String> commodityItems;
    private final Set<String> exactSafeItems;
    private final Set<String> containerItems;
    private final Set<String> unsupportedItems;
    private final boolean allowOrdinaryVanillaCommodities;

    private SafeItemCategoryRegistry(Builder builder) {
        commodityItems = Set.copyOf(builder.commodityItems);
        exactSafeItems = Set.copyOf(builder.exactSafeItems);
        containerItems = Set.copyOf(builder.containerItems);
        unsupportedItems = Set.copyOf(builder.unsupportedItems);
        allowOrdinaryVanillaCommodities = builder.allowOrdinaryVanillaCommodities;
    }

    public Category category(String normalizedItemId) {
        if (commodityItems.contains(normalizedItemId)) {
            return Category.COMMODITY;
        }
        if (exactSafeItems.contains(normalizedItemId)) {
            return Category.EXACT_SAFE;
        }
        if (containerItems.contains(normalizedItemId)) {
            return Category.CONTAINER;
        }
        if (unsupportedItems.contains(normalizedItemId)) {
            return Category.UNSUPPORTED;
        }

        int separator = normalizedItemId.indexOf(':');
        String namespace = separator >= 0 ? normalizedItemId.substring(0, separator) : "";
        String path = separator >= 0 ? normalizedItemId.substring(separator + 1) : normalizedItemId;
        if (!namespace.equals("minecraft")) {
            return Category.UNSUPPORTED;
        }
        if (DAMAGE_SENSITIVE_ITEMS.contains(path)
                || DAMAGE_SENSITIVE_SUFFIXES.stream().anyMatch(path::endsWith)
                || path.endsWith("_horse_armor")
                || path.equals("wolf_armor")
                || VISIBLE_METADATA_VALUE_ITEMS.contains(path)) {
            return Category.VISIBLE_METADATA_ONLY;
        }
        if (VALUE_METADATA_FAMILIES.contains(path)
                || path.endsWith("_banner")
                || path.endsWith("_spawn_egg")) {
            return Category.UNSUPPORTED;
        }
        if (allowOrdinaryVanillaCommodities) {
            return Category.COMMODITY;
        }
        return Category.UNREGISTERED;
    }

    public boolean damageMetadataRequired(String normalizedItemId) {
        String path = path(normalizedItemId);
        return DAMAGE_SENSITIVE_ITEMS.contains(path)
                || DAMAGE_SENSITIVE_SUFFIXES.stream().anyMatch(path::endsWith)
                || path.equals("wolf_armor");
    }

    public boolean valueMetadataUnavailable(String normalizedItemId) {
        String path = path(normalizedItemId);
        return VALUE_METADATA_FAMILIES.contains(path)
                || path.endsWith("_banner")
                || path.endsWith("_spawn_egg");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SafeItemCategoryRegistry safeDefaults() {
        return defaultBuilder().build();
    }

    /**
     * Production profile: every otherwise ordinary vanilla item is a fungible commodity.
     * Metadata-sensitive families, damageable equipment, and containers still take the
     * explicit branches above and therefore cannot bypass their dedicated safeguards.
     */
    public static SafeItemCategoryRegistry liveDefaults() {
        return defaultBuilder().allowOrdinaryVanillaCommodities().build();
    }

    private static Builder defaultBuilder() {
        Builder builder = builder();
        builder.addCommodityItems(Set.of(
                "minecraft:coal", "minecraft:charcoal",
                "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper",
                "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot",
                "minecraft:netherite_ingot", "minecraft:netherite_scrap",
                "minecraft:iron_nugget", "minecraft:gold_nugget",
                "minecraft:diamond", "minecraft:emerald", "minecraft:lapis_lazuli",
                "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:redstone",
                "minecraft:coal_block", "minecraft:raw_iron_block", "minecraft:raw_gold_block",
                "minecraft:raw_copper_block", "minecraft:iron_block", "minecraft:gold_block",
                "minecraft:copper_block", "minecraft:diamond_block", "minecraft:emerald_block",
                "minecraft:lapis_block", "minecraft:redstone_block", "minecraft:netherite_block",
                "minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrot",
                "minecraft:potato", "minecraft:beetroot", "minecraft:beetroot_seeds",
                "minecraft:melon_slice", "minecraft:pumpkin", "minecraft:sugar_cane",
                "minecraft:cactus", "minecraft:bamboo", "minecraft:kelp",
                "minecraft:dried_kelp", "minecraft:cocoa_beans", "minecraft:nether_wart",
                "minecraft:chorus_fruit", "minecraft:leather", "minecraft:string",
                "minecraft:feather", "minecraft:bone", "minecraft:bone_meal",
                "minecraft:gunpowder", "minecraft:glowstone_dust", "minecraft:blaze_rod",
                "minecraft:blaze_powder", "minecraft:ender_pearl", "minecraft:slime_ball",
                "minecraft:magma_cream", "minecraft:ghast_tear", "minecraft:prismarine_shard",
                "minecraft:prismarine_crystals", "minecraft:echo_shard",
                "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
                "minecraft:totem_of_undying"
        ));
        builder.addCommodityItems(MarketItemFamilyPolicy.oreItemIds());
        builder.addExactSafeItems(Set.of(
                "minecraft:beacon", "minecraft:conduit", "minecraft:dragon_egg",
                "minecraft:nether_star", "minecraft:heart_of_the_sea"
        ));
        builder.addContainerItems(shulkerBoxes());
        return builder;
    }

    private static Set<String> shulkerBoxes() {
        Set<String> ids = new HashSet<>();
        ids.add("minecraft:shulker_box");
        for (String color : Set.of(
                "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
        )) {
            ids.add("minecraft:" + color + "_shulker_box");
        }
        return ids;
    }

    private static String path(String normalizedItemId) {
        int separator = normalizedItemId.indexOf(':');
        return separator >= 0 ? normalizedItemId.substring(separator + 1) : normalizedItemId;
    }

    public static final class Builder {
        private final Set<String> commodityItems = new HashSet<>();
        private final Set<String> exactSafeItems = new HashSet<>();
        private final Set<String> containerItems = new HashSet<>();
        private final Set<String> unsupportedItems = new HashSet<>();
        private boolean allowOrdinaryVanillaCommodities;

        public Builder addCommodityItems(Collection<String> itemIds) {
            itemIds.forEach(id -> commodityItems.add(normalizeConfiguredId(id)));
            return this;
        }

        public Builder addExactSafeItems(Collection<String> itemIds) {
            itemIds.forEach(id -> exactSafeItems.add(normalizeConfiguredId(id)));
            return this;
        }

        public Builder addContainerItems(Collection<String> itemIds) {
            itemIds.forEach(id -> containerItems.add(normalizeConfiguredId(id)));
            return this;
        }

        public Builder addUnsupportedItems(Collection<String> itemIds) {
            itemIds.forEach(id -> unsupportedItems.add(normalizeConfiguredId(id)));
            return this;
        }

        public Builder allowOrdinaryVanillaCommodities() {
            allowOrdinaryVanillaCommodities = true;
            return this;
        }

        public SafeItemCategoryRegistry build() {
            return new SafeItemCategoryRegistry(this);
        }

        private String normalizeConfiguredId(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return normalized.contains(":") ? normalized : "minecraft:" + normalized;
        }
    }
}
