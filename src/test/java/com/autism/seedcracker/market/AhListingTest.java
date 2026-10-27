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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

    @Test
    void legacyStyledListingsRemainDistinctAndReachTheFlipperRanker() {
        long now = 1_800_000_000_000L;
        var engine = new com.autism.seedcracker.flip.FlipEngine(dir);
        var page = new java.util.ArrayList<com.autism.seedcracker.flip.core.FlipModel.Listing>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Seller: §fSeller" + i),
                Component.literal("§7Price: §a$" + (i == 0 ? "20,000" : "100,000")))));
            var listing = GuiListingReader.read(stack, now);
            assertNotNull(listing);
            assertEquals("seller" + i, listing.seller());
            page.add(listing);
        }
        engine.beginScan("auction house - diamond");
        engine.addPage(page);
        var deals = engine.rank(now);
        assertEquals(9, engine.liveListings(), "different sellers must not collapse into one unknown-seller ask");
        assertEquals(1, deals.size());
        assertEquals(20_000, deals.getFirst().buyPrice());
        assertEquals(com.autism.seedcracker.flip.core.FlipModel.Basis.ASKS, deals.getFirst().basis());
        assertFalse(AhGui.canAutoBuy(deals.getFirst(), "Me", now, 100_000));
    }

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

    @Test
    void contentsAndVariantItemsDoNotMasqueradeAsOrdinaryComparableListings() {
        for (var item : List.of(Items.SHULKER_BOX, Items.SPAWNER, Items.POTION, Items.PLAYER_HEAD, Items.BUNDLE)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Seller: Alice"), Component.literal("Price: $100"))));
            assertNull(GuiListingReader.itemKey(stack));
            assertNull(GuiListingReader.read(stack, 1_800_000_000_000L));
        }
        ItemStack filled = new ItemStack(Items.SHULKER_BOX);
        filled.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
        filled.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Seller: Bob"), Component.literal("Price: $100000"))));
        assertNull(GuiListingReader.read(filled, 1_800_000_000_000L));
    }

    @Test
    void cachedVariantBooksCannotBecomeRankedFlipsAfterAnUpgrade() {
        long now = 1_800_000_000_000L;
        var engine = new com.autism.seedcracker.flip.FlipEngine(dir);
        var page = new java.util.ArrayList<com.autism.seedcracker.flip.core.FlipModel.Listing>();
        for (String key : List.of("spawner", "shulker_box", "potion", "spawner+silk_touch1")) {
            for (int index = 0; index < 9; index++) {
                String seller = "seller" + index;
                long price = index == 0 ? 20_000 : 100_000;
                page.add(new com.autism.seedcracker.flip.core.FlipModel.Listing(key + "|" + seller, now, seller, key, 1, price));
            }
        }
        engine.beginScan("auction house");
        engine.addPage(page);
        assertTrue(engine.rank(now).isEmpty());
        assertTrue(engine.rejections().stream().anyMatch(reason -> reason.contains("Unsupported variant metadata")));
    }
}
