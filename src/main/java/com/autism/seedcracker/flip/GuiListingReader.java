package com.autism.seedcracker.flip;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.SaleInference;
import com.autism.seedcracker.market.ListingPriceParser;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
        StringBuilder sb = new StringBuilder(id.getPath());
        ItemEnchantments ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) {
            java.util.TreeMap<String, Integer> sorted = new java.util.TreeMap<>();
            for (var e : ench.entrySet()) {
                sorted.put(e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), e.getIntValue());
            }
            sorted.forEach((k, v) -> sb.append('+').append(k).append(v));
        }
        return sb.toString();
    }

    static String seller(ItemStack stack) {
        try {
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY,
                Minecraft.getInstance().player, TooltipFlag.Default.NORMAL);
            for (Component c : tooltip) {
                Matcher m = SELLER.matcher(c.getString());
                if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}
        return "?";
    }
}
