package com.autism.seedcracker.market;

import java.util.List;

import com.autism.seedcracker.flip.GuiListingReader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AhListingTest {
    @BeforeAll
    static void bootstrapItems() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.2 binds item prototypes during data-pack reload; this fixture only needs the common defaults.
        Items.DIAMOND.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
    }

    @Test
    void onlyListingLoreSuppliesThePriceAndStyledSeller() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 16);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Price: $1"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Seller: ").append(Component.literal("Alice").withStyle(ChatFormatting.GREEN)),
            Component.literal("Price: $1,600"), Component.literal("Each: $100"))));
        assertEquals(1600, ListingPriceParser.parse(stack));
        var listing = GuiListingReader.read(stack, 1_800_000_000_000L);
        assertNotNull(listing);
        assertEquals("alice", listing.seller());
        assertEquals("diamond", listing.itemKey());
        assertEquals(16, listing.count());
        assertEquals(1600, listing.totalPrice());
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Seller: Alice"), Component.literal("Price: $2,000"))));
        assertFalse(AhGui.sameListing(listing, GuiListingReader.read(stack, listing.observedAt() + 1)));
    }

    @Test
    void renamedUnpricedItemsAndMissingLoreAreNotBuyable() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Price: $1"));
        assertEquals(-1, ListingPriceParser.parse(stack));
        assertNull(GuiListingReader.read(stack, 1_800_000_000_000L));
        assertNull(GuiListingReader.read(ItemStack.EMPTY, 1_800_000_000_000L));
    }
}
