package com.autism.seedcracker.market;

import java.util.Locale;

import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Shared auction GUI identification, paging and purchase checks. */
public final class AhGui {
    private AhGui() {}

    private static volatile long lastPageTurnMs;
    private static boolean purchasing;

    public static void notePageTurn() {
        lastPageTurnMs = System.currentTimeMillis();
    }

    public static boolean pagedWithin(long ms) {
        return System.currentTimeMillis() - lastPageTurnMs < ms;
    }

    /** Readers must not turn pages or reopen /ah while the sniper is buying. */
    public static boolean purchasing() {
        return purchasing;
    }

    public static void setPurchasing(boolean buying) {
        purchasing = buying;
    }

    /** Slots before the first player-inventory slot; never includes the 36 inventory slots. */
    public static int containerSlots(Minecraft mc, AbstractContainerMenu menu) {
        if (mc == null || mc.player == null || menu == null) return 0;
        int n = 0;
        for (var slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) break;
            n++;
        }
        return n;
    }

    public static boolean isListingPage(int containerSlots) {
        return containerSlots >= 45;
    }

    public static String screenTitle(Minecraft mc) {
        return mc.gui.screen() == null ? "" : mc.gui.screen().getTitle().getString();
    }

    public static boolean isAuctionPage(Minecraft mc, AbstractContainerMenu menu) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> && menu == mc.player.containerMenu
            && isListingPage(containerSlots(mc, menu)) && isAuctionTitle(screenTitle(mc))
            && !isConfirmTitle(screenTitle(mc));
    }

    public static boolean isAuctionTitle(String title) {
        String name = label(title);
        return name.contains("auction") || name.equals("ah") || name.startsWith("ah ");
    }

    public static boolean isConfirmTitle(String title) {
        String name = label(title);
        return name.contains("confirm") || name.contains("purchase") || name.contains("buy item");
    }

    public static boolean isConfirmDialog(int slots, String title) {
        return slots > 0 && slots <= 54
            && (isConfirmTitle(title) || (slots < 45 && isAuctionTitle(title)));
    }

    public static boolean isConfirmButton(String name, String itemId) {
        name = label(name);
        itemId = label(itemId);
        if (name.contains("cancel") || name.contains("deny") || name.contains("decline")
            || name.contains("back") || name.contains("do not") || itemId.startsWith("red_")) return false;
        return name.contains("confirm") || name.contains("purchase") || name.equals("buy")
            || name.startsWith("buy ") || itemId.startsWith("lime_") || itemId.startsWith("green_");
    }

    /** Revalidate every identifying field, not just the item's registry id or the slot index. */
    public static boolean sameListing(Listing expected, Listing actual) {
        return expected != null && actual != null && expected.isValid() && actual.isValid()
            && expected.itemKey().equals(actual.itemKey()) && expected.count() == actual.count()
            && expected.totalPrice() == actual.totalPrice()
            && expected.seller() != null && actual.seller() != null
            && expected.seller().equalsIgnoreCase(actual.seller());
    }

    public static boolean delivered(int before, int after, int quantity) {
        return before >= 0 && quantity > 0 && (long) after - before >= quantity;
    }

    public static boolean canAutoBuy(Opportunity opportunity, String localPlayer, long now, long remainingBudget) {
        if (opportunity == null || opportunity.basis() == null || opportunity.basis() == Basis.ASKS) return false;
        Listing listing = opportunity.listing();
        return listing != null && listing.isValid() && listing.observedAt() > 0
            && listing.observedAt() <= now && now - listing.observedAt() <= 30_000
            && listing.seller() != null && listing.seller().matches("[A-Za-z0-9_]{3,16}")
            && !listing.seller().equalsIgnoreCase(localPlayer)
            && opportunity.buyPrice() == listing.totalPrice() && opportunity.buyPrice() <= remainingBudget
            && opportunity.sellPrice() > opportunity.buyPrice()
            && Double.isFinite(opportunity.expectedProfit()) && opportunity.expectedProfit() > 0
            && Double.isFinite(opportunity.roiPercent()) && opportunity.roiPercent() > 0
            && Double.isFinite(opportunity.confidence()) && opportunity.confidence() > 0;
    }

    public static int findNextPageSlot(AbstractContainerMenu menu, int containerSlots) {
        for (int i = Math.min(containerSlots, menu.slots.size()) - 1; i >= 0; i--) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) continue;
            String name = label(s.getHoverName().getString());
            if (name.contains("next") || name.contains("→") || name.contains("->") || name.contains(">>")) {
                if (ListingPriceParser.parse(s) > 0) continue;
                return i;
            }
        }
        return -1;
    }

    public static int findConfirmSlot(AbstractContainerMenu menu, int containerSlots, int previewSlot) {
        for (int i = 0; i < Math.min(containerSlots, menu.slots.size()); i++) {
            if (i == previewSlot) continue;
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && isConfirmButton(stack.getHoverName().getString(), id.getPath())) return i;
        }
        return -1;
    }

    public static int findRefreshSlot(AbstractContainerMenu menu, int containerSlots) {
        for (int i = Math.min(containerSlots, menu.slots.size()) - 1; i >= 0; i--) {
            ItemStack s = menu.slots.get(i).getItem();
            if (!s.isEmpty() && label(s.getHoverName().getString()).contains("refresh")
                && ListingPriceParser.parse(s) <= 0) return i;
        }
        return -1;
    }

    private static String label(String value) {
        return value == null ? "" : value.replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim().toLowerCase(Locale.ROOT);
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
