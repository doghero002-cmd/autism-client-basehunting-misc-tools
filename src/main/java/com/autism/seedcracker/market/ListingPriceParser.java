package com.autism.seedcracker.market;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

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
            if (stack == null || stack.isEmpty()) return -1;
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return -1;
            double best = -1;
            for (var c : lore.lines()) {
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
