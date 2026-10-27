package com.autism.seedcracker.market;

import java.util.List;

import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AhGuiTest {
    @Test void sellingRequiresExplicitItemsUnlessAllHotbarSalesWereOptedIn() {
        assertFalse(AhGui.saleItemAllowed("minecraft:diamond", "", false));
        assertTrue(AhGui.saleItemAllowed("minecraft:diamond", "", true));
        assertTrue(AhGui.saleItemAllowed("minecraft:cobblestone", "cobblestone, stone", false));
        assertFalse(AhGui.saleItemAllowed("minecraft:diamond", "cobblestone, stone", false));
        assertFalse(AhGui.saleItemAllowed("minecraft:diamond", "diamond, bad command", false));
    }

    @Test void saleConfirmationCannotBeAnOrdinaryChestOrPurchaseDialog() {
        assertTrue(AhGui.saleConfirmation(27, "Confirm Sell"));
        assertTrue(AhGui.saleConfirmation(54, "Confirm Listing"));
        assertFalse(AhGui.saleConfirmation(27, "Chest"));
        assertFalse(AhGui.saleConfirmation(54, "Auction House"));
        assertFalse(AhGui.saleConfirmation(27, "Confirm Purchase"));
        assertFalse(AhGui.saleConfirmation(63, "Confirm Sell"));
    }

    @Test
    void sniperTargetFiltersDistinguishNamedVariantsAndStackSizes() {
        assertTrue(AhGui.targetMatches("\u00a7bShard Netherite Helmet", 1, "shard", "expired", 1));
        assertFalse(AhGui.targetMatches("Netherite Helmet", 1, "shard", "", 1));
        assertFalse(AhGui.targetMatches("Expired Shard Helmet", 1, "shard", "expired", 1));
        assertFalse(AhGui.targetMatches("Netherite Ingot", 3, "", "", 4));
        assertTrue(AhGui.targetMatches("Netherite Ingot", 4, "", "", 4));
        assertTrue(AhGui.targetMatches("Shard Helmet", 1, " SHARD ", "", 1));
        assertFalse(AhGui.targetMatches(null, 64, "", "", 1));
    }

    private static final long NOW = 1_800_000_000_000L;

    private static Listing listing(long time, String seller, String item, int count, long price) {
        return new Listing("listing", time, seller, item, count, price);
    }

    private static Opportunity opportunity(Listing listing, Basis basis) {
        return new Opportunity(listing, null, basis, listing.totalPrice(), 2000, 1000,
            100, 0.1, 0.9, 0.8, 1, List.of());
    }

    @Test
    void scanIdentityIgnoresOnlyPageSuffixesNotSearchOrSortTerms() {
        assertEquals("auction house", AhGui.scanKey("§6Auction House - Page 1"));
        assertEquals("auction house - elytra", AhGui.scanKey("Auction House - Elytra (Page 2 of 7)"));
        assertEquals(AhGui.scanKey("Auction House [Page 1/2]"), AhGui.scanKey("Auction House [Page 2/2]"));
        assertEquals("", AhGui.scanKey(null));
        assertEquals(0, AhGui.pageNumber("Auction House"));
        assertEquals(1, AhGui.pageNumber("§6Auction House [Page 1/2]"));
        assertEquals(2, AhGui.pageNumber("Auction House - Page 2 of 4"));
        assertTrue(AhGui.pageNumber("Auction House - Page 99999999999999999999") > 1);
        assertNotEquals(AhGui.scanKey("Auction House - Diamond"), AhGui.scanKey("Auction House - Elytra"));
        assertNotEquals(AhGui.scanKey("Auction House - Lowest price"), AhGui.scanKey("Auction House - Recent"));
    }

    @Test
    void confirmationUsesContainerSlotsNotTheCombinedInventorySize() {
        assertTrue(AhGui.isConfirmDialog(9, "Confirm Purchase"));
        assertTrue(AhGui.isConfirmDialog(27, "Confirm Purchase")); // 27 + 36 inventory slots = 63.
        assertTrue(AhGui.isConfirmDialog(54, "Confirm Purchase"));
        assertTrue(AhGui.isConfirmDialog(27, "Auction House"));
        assertFalse(AhGui.isConfirmDialog(63, "Confirm Purchase"));
        assertFalse(AhGui.isConfirmDialog(54, "Auction House"));
        assertFalse(AhGui.isConfirmDialog(27, "Chest"));
        assertFalse(AhGui.isConfirmDialog(0, "Confirm Purchase"));
        assertTrue(AhGui.isAuctionTitle("§6Auction House - Page 1"));
        assertFalse(AhGui.isAuctionTitle("Storage Chest"));
        assertTrue(AhGui.isConfirmTitle("Auction House - Confirm Purchase"));
        assertTrue(AhGui.isConfirmTitle("Confirm Purchase"));
        assertTrue(AhGui.isConfirmTitle("Buy Item"));
        assertFalse(AhGui.isConfirmTitle("Large Chest"));
    }

    @Test
    void cancelButtonsAndOrdinaryValuablesAreNeverConfirmationButtons() {
        assertTrue(AhGui.isConfirmButton("§aConfirm", "lime_stained_glass_pane"));
        assertTrue(AhGui.isConfirmButton("Buy item", "green_concrete"));
        assertFalse(AhGui.isConfirmButton("Confirm cancellation", "lime_stained_glass_pane"));
        assertFalse(AhGui.isConfirmButton("Buy", "red_concrete"));
        assertFalse(AhGui.isConfirmButton("Do not buy", "green_concrete"));
        assertFalse(AhGui.isConfirmButton("Emerald", "emerald"));
    }

    @Test
    void theLiveListingMustMatchSellerCountFullPriceAndEnchantments() {
        Listing expected = listing(NOW, "Alice", "diamond_sword+sharpness5", 1, 1000);
        assertTrue(AhGui.sameListing(expected, listing(NOW + 1, "ALICE", expected.itemKey(), 1, 1000)));
        assertFalse(AhGui.sameListing(expected, listing(NOW, "Bob", expected.itemKey(), 1, 1000)));
        assertFalse(AhGui.sameListing(expected, listing(NOW, "Alice", expected.itemKey(), 64, 1000)));
        assertFalse(AhGui.sameListing(expected, listing(NOW, "Alice", expected.itemKey(), 1, 1001)));
        assertFalse(AhGui.sameListing(expected, listing(NOW, "Alice", "diamond_sword", 1, 1000)));
        assertFalse(AhGui.sameListing(expected, null));
    }

    @Test
    void autoBuyRequiresFreshSalesEvidenceAndARealCappedSeller() {
        Listing live = listing(NOW, "Alice", "diamond", 16, 1000);
        assertTrue(AhGui.canAutoBuy(opportunity(live, Basis.SALES), "Me", NOW, 1000));
        assertTrue(AhGui.canAutoBuy(opportunity(live, Basis.INFERRED), "Me", NOW, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(live, Basis.ASKS), "Me", NOW, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(live, Basis.SALES), "Me", NOW, 999));
        assertFalse(AhGui.canAutoBuy(opportunity(live, Basis.SALES), "aLiCe", NOW, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(live, Basis.SALES), "Me", NOW + 30_001, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(live, Basis.SALES), "Me", NOW - 1, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(listing(NOW, "?", "diamond", 16, 1000), Basis.SALES), "Me", NOW, 1000));
        assertFalse(AhGui.canAutoBuy(opportunity(listing(NOW, "Alice;pay", "diamond", 16, 1000), Basis.SALES), "Me", NOW, 1000));
    }

    @Test
    void partialInventoryGrowthIsNotAConfirmedFullStackPurchase() {
        assertFalse(AhGui.delivered(64, 65, 64));
        assertTrue(AhGui.delivered(64, 128, 64));
        assertFalse(AhGui.delivered(-1, 64, 64));
        assertFalse(AhGui.delivered(0, 1, 0));
    }
}
