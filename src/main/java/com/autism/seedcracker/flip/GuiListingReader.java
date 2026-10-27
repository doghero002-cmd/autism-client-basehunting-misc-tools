package com.autism.seedcracker.flip;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.SaleInference;
import com.autism.seedcracker.market.ListingPriceParser;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Turns one AH GUI slot into a {@link Listing}: item key, count, lore price and seller. */
public final class GuiListingReader {
    private GuiListingReader() {}

    private static final Pattern SELLER = Pattern.compile("(?i)(?:seller|sold by|owner|by)\\s*[:\\-]?\\s*([A-Za-z0-9_]{3,16})");

    /** Null when the slot isn't a priced listing. */
    public static Listing read(ItemStack stack, long now) {
        if (stack == null || stack.isEmpty()) return null;
        double price = ListingPriceParser.parse(stack);
        if (price <= 0 || price > Long.MAX_VALUE / 2) return null;
        String key = itemKey(stack);
        if (key == null) return null;
        String seller = seller(stack);
        long total = Math.round(price);
        return new Listing(SaleInference.listingKey(seller, key, stack.getCount(), total), now, seller, key,
            stack.getCount(), total);
    }

    /**
     * Market identity: base id plus sorted enchantments, so a Prot IV chestplate never prices
     * against a bare one. Matches the API key shape built by {@link ApiFlipSource#itemKey}.
     */
    public static String itemKey(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return null;
        String base = marketBaseKey(id.toString());
        if (base == null) return null;
        StringBuilder sb = new StringBuilder(base);
        ItemEnchantments ench = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (ench == null || ench.isEmpty()) ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) {
            java.util.TreeMap<String, Integer> sorted = new java.util.TreeMap<>();
            for (var e : ench.entrySet()) {
                sorted.put(e.getKey().unwrapKey().map(k -> {
                    String name = k.identifier().toString();
                    return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
                }).orElse("?"), e.getIntValue());
            }
            sorted.forEach((k, v) -> sb.append('+').append(k).append(v));
        }
        return sb.toString();
    }

    static String marketBaseKey(String raw) {
        if (raw == null) return null;
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (java.util.Set.of("spawner", "trial_spawner", "vault", "potion", "splash_potion", "lingering_potion",
                "tipped_arrow", "player_head", "player_wall_head", "bundle", "suspicious_stew", "firework_rocket", "firework_star").contains(path)
            || path.endsWith("shulker_box") || path.endsWith("_bundle")) return null;
        return id.startsWith("minecraft:") ? path : id;
    }

    static String seller(ItemStack stack) {
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return "?";
            for (var c : lore.lines()) {
                Matcher m = SELLER.matcher(net.minecraft.ChatFormatting.stripFormatting(c.getString()));
                if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}
        return "?";
    }
}
