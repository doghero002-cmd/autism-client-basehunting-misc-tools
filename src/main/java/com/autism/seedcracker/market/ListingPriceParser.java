package com.autism.seedcracker.market;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Anchored auction-listing price parser shared by the AH modules and the price tracker.
 * Only lines that LOOK like a price line count ($ prefix or a price/cost/buy keyword) -
 * matching "the lowest number anywhere" silently treats enchant levels and stack counts
 * as prices.
 */
public final class ListingPriceParser {
    private ListingPriceParser() {}

    /** Listing price from the stack's tooltip, or -1 if no price-looking line exists. */
    public static double parse(ItemStack stack) {
        try {
            List<Component> tooltip = stack.getTooltipLines(
                Item.TooltipContext.EMPTY, Minecraft.getInstance().player, TooltipFlag.Default.NORMAL);
            double best = -1;
            for (Component c : tooltip) {
                double v = com.autism.seedcracker.util.pure.PriceMath.parseLine(c.getString());
                if (v < 0) continue;
                if (best < 0 || v < best) best = v;
            }
            return best;
        } catch (Throwable t) {
            return -1;
        }
    }
}
