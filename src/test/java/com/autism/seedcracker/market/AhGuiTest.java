package com.autism.seedcracker.market;

import java.util.List;

import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AhGuiTest {
    private static final long NOW = 1_800_000_000_000L;

    private static Listing listing(long time, String seller, String item, int count, long price) {
        return new Listing("listing", time, seller, item, count, price);
    }

    private static Opportunity opportunity(Listing listing, Basis basis) {
        return new Opportunity(listing, null, basis, listing.totalPrice(), 2000, 1000,
            100, 0.1, 0.9, 0.8, 1, List.of());
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
