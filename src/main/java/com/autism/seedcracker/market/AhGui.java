package com.autism.seedcracker.market;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Shared auction-house GUI helpers (Price Check and AH Flipper read the same screens). */
public final class AhGui {
    private AhGui() {}

    // Shared so Price Check and AH Flipper auto-paging never both click "next" on the same page.
    private static volatile long lastPageTurnMs;

    public static void notePageTurn() {
        lastPageTurnMs = System.currentTimeMillis();
    }

    public static boolean pagedWithin(long ms) {
        return System.currentTimeMillis() - lastPageTurnMs < ms;
    }

    /** Slots before the first player-inventory slot. */
    public static int containerSlots(Minecraft mc, AbstractContainerMenu menu) {
        int n = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container == mc.player.getInventory()) break;
            n++;
        }
        return n;
    }

    /** AH pages are 45+ slots; smaller GUIs (furnaces, confirm dialogs) are not listing pages. */
    public static boolean isListingPage(int containerSlots) {
        return containerSlots >= 45;
    }

    /** A "next page" control: arrow/paper/etc. named next, that isn't itself a priced listing. -1 if none. */
    public static int findNextPageSlot(AbstractContainerMenu menu, int containerSlots) {
        for (int i = containerSlots - 1; i >= 0; i--) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) continue;
            String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("next") || name.contains("→") || name.contains("->") || name.contains(">>")) {
                if (ListingPriceParser.parse(s) > 0) continue;
                return i;
            }
        }
        return -1;
    }

    /** "minecraft:diamond_block+sharpness5" -> "diamond block" (AH search takes display-ish names). */
    public static String searchName(String itemKey) {
        String s = itemKey.trim();
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.replace('_', ' ');
    }
}
