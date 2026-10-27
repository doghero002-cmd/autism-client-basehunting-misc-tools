package com.autism.seedcracker.flip;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiEnchantment;
import com.example.donutflipscanner.api.model.ApiItemData;
import com.example.donutflipscanner.api.model.ApiSeller;

import static org.junit.jupiter.api.Assertions.*;

class ApiFlipSourceTest {

    private static ApiAuctionItem item(String id, int count, ApiEnchantment... ench) {
        Optional<ApiItemData> data = ench.length == 0 ? Optional.empty() : Optional.of(new ApiItemData(List.of(ench), Optional.empty()));
        return new ApiAuctionItem(Optional.of(id), OptionalInt.of(count), Optional.empty(), List.of(), data, List.of());
    }

    @Test
    void itemKeyMatchesGuiShape() {
        // GuiListingReader builds "path" + sorted "+enchLevel" - the API key must be identical.
        assertEquals("diamond_chestplate+protection4+unbreaking3", ApiFlipSource.itemKey(item("minecraft:diamond_chestplate", 1,
            new ApiEnchantment("minecraft:unbreaking", 3), new ApiEnchantment("minecraft:protection", 4))));
        assertEquals("ender_pearl", ApiFlipSource.itemKey(item("ENDER_PEARL", 16)));
        assertEquals("custom:ender_pearl", ApiFlipSource.itemKey(item("custom:ender_pearl", 16)));
        assertEquals("diamond_chestplate+custom:protection4", ApiFlipSource.itemKey(item("minecraft:diamond_chestplate", 1,
            new ApiEnchantment("custom:protection", 4))));
    }

    @Test
    void unrepresentedVariantsCannotSupplyComparablesOrBuyCandidates() {
        for (String id : List.of("spawner", "potion", "splash_potion", "lingering_potion", "tipped_arrow", "player_head",
            "shulker_box", "blue_shulker_box", "bundle", "red_bundle", "firework_rocket", "suspicious_stew")) {
            assertNull(ApiFlipSource.itemKey(item("minecraft:" + id, 1)), id);
            var seller = new ApiSeller(Optional.of("Bob"), Optional.empty());
            assertTrue(ApiFlipSource.toListings(List.of(new ApiAuctionListing(Optional.of(item("minecraft:" + id, 1)),
                Optional.of(new BigDecimal("10000")), Optional.of(seller), OptionalLong.empty())), 5L).isEmpty(), id);
            assertTrue(ApiFlipSource.toSales(List.of(new ApiCompletedTransaction(Optional.of(item("minecraft:" + id, 1)),
                Optional.of(new BigDecimal("10000")), Optional.of(seller), Optional.of(BigInteger.valueOf(1_800_000_000_000L))))).isEmpty(), id);
        }
        assertNull(ApiFlipSource.itemKey(item("minecraft:diamond+fake", 1)));
    }

    @Test
    void transactionsMapToSalesAndSkipIncomplete() {
        ApiSeller seller = new ApiSeller(Optional.of("Bob"), Optional.empty());
        List<Sale> sales = ApiFlipSource.toSales(List.of(
            new ApiCompletedTransaction(Optional.of(item("minecraft:ender_pearl", 16)), Optional.of(new BigDecimal("12000.4")),
                Optional.of(seller), Optional.of(BigInteger.valueOf(1_800_000_000_000L))),
            new ApiCompletedTransaction(Optional.of(item("minecraft:ender_pearl", 16)), Optional.empty(),
                Optional.of(seller), Optional.of(BigInteger.ONE))));
        assertEquals(1, sales.size());
        Sale s = sales.get(0);
        assertEquals(12_000, s.totalPrice());
        assertEquals("bob", s.seller());
        assertFalse(s.inferred());
        assertEquals(1_800_000_000_000L, s.soldAt());
    }

    @Test
    void listingKeysMatchSaleIdentitySoSalesClearTheBook() {
        ApiSeller seller = new ApiSeller(Optional.of("Bob"), Optional.empty());
        List<Listing> ls = ApiFlipSource.toListings(List.of(new ApiAuctionListing(Optional.of(item("minecraft:ender_pearl", 16)),
            Optional.of(new BigDecimal("12000")), Optional.of(seller), OptionalLong.empty())), 5L);
        assertEquals("bob|ender_pearl|16|12000", ls.get(0).listingKey());
    }
}
